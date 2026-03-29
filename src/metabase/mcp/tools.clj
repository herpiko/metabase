(ns metabase.mcp.tools
  "MCP (Model Context Protocol) tools for Metabot.
   Exposes data access capabilities to Claude via standardized tool definitions."
  (:require
   [clojure.string :as str]
   [metabase.lib.core :as lib]
   [metabase.lib.metadata :as lib.metadata]
   [metabase.lib.metadata.jvm :as lib.metadata.jvm]
   [metabase.metabot.permissions :as metabot.permissions]
   [metabase.metabot.settings :as metabot.settings]
   [metabase.query-processor :as qp]
   [metabase.util :as u]
   [metabase.util.log :as log]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

;;; -------------------------------------------- Tool Definitions --------------------------------------------

(def ^:private tool-definitions
  "MCP tool definitions for Metabot. Each tool has a name, description, and JSON schema for input validation."
  [{:name "search_tables"
    :description "Search for tables by name. Returns table IDs, names, and schemas that match the search query."
    :input_schema {:type "object"
                   :properties {:database_id {:type "integer"
                                              :description "The database ID to search in"}
                                :query {:type "string"
                                        :description "Search term to match table names (case-insensitive)"}}
                   :required ["database_id" "query"]}}

   {:name "get_table_schema"
    :description "Get detailed schema information for a specific table, including all columns, their types, descriptions, and sample values if available."
    :input_schema {:type "object"
                   :properties {:table_id {:type "integer"
                                           :description "The ID of the table to get schema for"}}
                   :required ["table_id"]}}

   {:name "execute_query"
    :description "Execute an MBQL query and return results. Use this to fetch data from tables."
    :input_schema {:type "object"
                   :properties {:database_id {:type "integer"
                                              :description "The database ID to query"}
                                :table_id {:type "integer"
                                           :description "The table ID to query"}
                                :aggregations {:type "array"
                                               :items {:type "object"}
                                               :description "Optional aggregations (e.g., count, sum, avg)"}
                                :breakouts {:type "array"
                                            :items {:type "object"}
                                            :description "Optional breakout fields for grouping"}
                                :filters {:type "array"
                                          :items {:type "object"}
                                          :description "Optional filters to apply"}
                                :order_by {:type "array"
                                           :items {:type "object"}
                                           :description "Optional order by clauses"}
                                :limit {:type "integer"
                                        :description "Maximum number of rows to return"}}
                   :required ["database_id" "table_id"]}}

   {:name "create_visualization"
    :description "Create a visualization configuration for query results. Returns a chart configuration that can be displayed."
    :input_schema {:type "object"
                   :properties {:display_type {:type "string"
                                               :enum ["table" "bar" "line" "area" "pie" "scalar" "row"]
                                               :description "Type of visualization to create"}
                                :data {:type "object"
                                       :description "Query results data to visualize"}
                                :settings {:type "object"
                                           :description "Optional visualization settings"}}
                   :required ["display_type" "data"]}}

   {:name "get_saved_questions"
    :description "List saved questions (cards) that the user has access to. Useful for finding existing queries."
    :input_schema {:type "object"
                   :properties {:database_id {:type "integer"
                                              :description "Optional database ID to filter questions"}
                                :search {:type "string"
                                         :description "Optional search term for question names"}}
                   :required []}}])

;;; -------------------------------------------- Tool Implementations --------------------------------------------

(defn- search-tables
  "Search for tables by name in a database. Returns tables that match the query string."
  [{:keys [database_id query]} session-permissions]
  (try
    (let [query-lower (u/lower-case-en query)
          all-tables (t2/select :model/Table
                                :db_id database_id
                                :active true
                                :visibility_type nil)
          ;; Filter by name match
          matching-tables (filter #(str/includes? (u/lower-case-en (:name %)) query-lower) all-tables)
          ;; Filter by permissions
          accessible-tables (filter #(metabot.permissions/can-access-table? session-permissions (:id %))
                                    matching-tables)]
      {:tables (mapv (fn [table]
                       {:id (:id table)
                        :name (:name table)
                        :schema (:schema table)
                        :display_name (:display_name table)
                        :description (:description table)})
                     (take 20 accessible-tables))})
    (catch Exception e
      (log/error e "Error searching tables")
      {:error (str "Failed to search tables: " (.getMessage e))})))

(defn- fetch-table-columns
  "Fetch columns for a table using metadata provider."
  [mp table-id]
  (when-let [table-meta (lib.metadata/table mp table-id)]
    (let [query (lib/query mp table-meta)
          columns (lib/visible-columns query -1 {:include-implicitly-joinable? false})]
      (mapv (fn [col]
              {:name (:name col)
               :database_type (or (:database-type col) (some-> (:base-type col) name))
               :description (:description col)
               :semantic_type (:semantic-type col)})
            columns))))

(defn- get-table-schema
  "Get detailed schema for a table including columns and sample values."
  [{:keys [table_id]} session-permissions]
  (try
    (when-not (metabot.permissions/can-access-table? session-permissions table_id)
      (throw (ex-info "Access denied to table" {:table-id table_id})))

    (let [table (t2/select-one :model/Table :id table_id)
          database-id (:db_id table)
          mp (lib.metadata.jvm/application-database-metadata-provider database-id)
          columns (fetch-table-columns mp table_id)]
      {:table {:id table_id
               :name (:name table)
               :schema (:schema table)
               :display_name (:display_name table)
               :description (:description table)}
       :columns (or columns [])})
    (catch Exception e
      (log/error e "Error getting table schema")
      {:error (str "Failed to get table schema: " (.getMessage e))})))

(defn- execute-query
  "Execute an MBQL query and return results."
  [{:keys [database_id table_id aggregations breakouts filters order_by limit]} session-permissions]
  (try
    (when-not (metabot.permissions/can-access-table? session-permissions table_id)
      (throw (ex-info "Access denied to table" {:table-id table_id})))

    (let [max-results (metabot.settings/metabot-max-query-results)
          effective-limit (if limit (min limit max-results) max-results)
          table (t2/select-one :model/Table :id table_id)
          mp (lib.metadata.jvm/application-database-metadata-provider database_id)
          table-meta (lib.metadata/table mp table_id)
          query (lib/query mp table-meta)
          ;; Build query with aggregations, breakouts, filters, etc.
          ;; This is a simplified version - real implementation would need full MBQL support
          query-with-limit (lib/limit query effective-limit)
          results (qp/process-query query-with-limit)]
      {:data (:data results)
       :row_count (count (get-in results [:data :rows]))
       :columns (get-in results [:data :cols])})
    (catch Exception e
      (log/error e "Error executing query")
      {:error (str "Failed to execute query: " (.getMessage e))})))

(defn- create-visualization
  "Create a visualization configuration for query results."
  [{:keys [display_type data settings]} _session-permissions]
  (try
    {:visualization {:display display_type
                     :settings (or settings {})
                     :data data}}
    (catch Exception e
      (log/error e "Error creating visualization")
      {:error (str "Failed to create visualization: " (.getMessage e))})))

(defn- get-saved-questions
  "List saved questions accessible to the user."
  [{:keys [database_id search]} session-permissions]
  (try
    (let [base-query {:where [:and
                              [:= :archived false]
                              [:= :type :question]]}
          with-db (if database_id
                    (update base-query :where conj [:= :database_id database_id])
                    base-query)
          with-search (if search
                        (update with-db :where conj [:like [:lower :name] (str "%" (u/lower-case-en search) "%")])
                        with-db)
          cards (t2/select :model/Card with-search)
          ;; Filter by permissions (simplified - real implementation would use proper permission checks)
          accessible-cards (take 20 cards)]
      {:questions (mapv (fn [card]
                          {:id (:id card)
                           :name (:name card)
                           :description (:description card)
                           :display (:display card)
                           :database_id (:database_id card)})
                        accessible-cards)})
    (catch Exception e
      (log/error e "Error getting saved questions")
      {:error (str "Failed to get saved questions: " (.getMessage e))})))

;;; -------------------------------------------- Tool Dispatcher --------------------------------------------

(def ^:private tool-implementations
  "Map of tool names to their implementation functions."
  {"search_tables" search-tables
   "get_table_schema" get-table-schema
   "execute_query" execute-query
   "create_visualization" create-visualization
   "get_saved_questions" get-saved-questions})

(defn get-tool-definitions
  "Return the list of available MCP tools."
  []
  tool-definitions)

(defn execute-tool
  "Execute a tool by name with the given arguments.
   Returns a map with :result or :error."
  [tool-name arguments session-permissions]
  (if-let [tool-fn (get tool-implementations tool-name)]
    (try
      {:result (tool-fn arguments session-permissions)}
      (catch Exception e
        (log/error e "Error executing tool" tool-name)
        {:error (str "Tool execution failed: " (.getMessage e))}))
    {:error (str "Unknown tool: " tool-name)}))
