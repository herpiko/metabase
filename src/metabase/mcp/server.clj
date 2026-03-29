(ns metabase.mcp.server
  "JSON-RPC server implementation for Model Context Protocol (MCP).
   Handles tool discovery and execution for Claude."
  (:require
   [metabase.mcp.tools :as mcp.tools]
   [metabase.util.json :as json]
   [metabase.util.log :as log]))

(set! *warn-on-reflection* true)

;;; -------------------------------------------- JSON-RPC Protocol --------------------------------------------

(defn- make-response
  "Create a JSON-RPC 2.0 response."
  [id result]
  {:jsonrpc "2.0"
   :id id
   :result result})

(defn- make-error-response
  "Create a JSON-RPC 2.0 error response."
  [id code message]
  {:jsonrpc "2.0"
   :id id
   :error {:code code
           :message message}})

;;; -------------------------------------------- MCP Methods --------------------------------------------

(defn- handle-list-tools
  "Handle the 'tools/list' method - returns available tools."
  [_params _session-permissions]
  {:tools (mcp.tools/get-tool-definitions)})

(defn- handle-call-tool
  "Handle the 'tools/call' method - execute a tool with given arguments."
  [params session-permissions]
  (let [{:keys [name arguments]} params]
    (when-not name
      (throw (ex-info "Missing tool name" {:code -32602})))

    (log/debug "Executing MCP tool:" name "with arguments:" arguments)

    (let [result (mcp.tools/execute-tool name arguments session-permissions)]
      (if (:error result)
        {:content [{:type "text"
                    :text (str "Error: " (:error result))}]
         :isError true}
        {:content [{:type "text"
                    :text (json/encode (:result result))}]}))))

(defn- handle-initialize
  "Handle the 'initialize' method - returns server capabilities."
  [_params _session-permissions]
  {:protocolVersion "2024-11-05"
   :capabilities {:tools {:listChanged false}}
   :serverInfo {:name "metabase-mcp"
                :version "1.0.0"}})

;;; -------------------------------------------- Request Dispatcher --------------------------------------------

(def ^:private method-handlers
  "Map of MCP method names to handler functions."
  {"initialize" handle-initialize
   "tools/list" handle-list-tools
   "tools/call" handle-call-tool})

(defn handle-request
  "Handle a JSON-RPC request from Claude.
   Takes a request map with :method, :params, :id and session permissions.
   Returns a JSON-RPC response map."
  [request session-permissions]
  (let [{:keys [method params id]} request]
    (try
      (if-let [handler (get method-handlers method)]
        (let [result (handler params session-permissions)]
          (make-response id result))
        (make-error-response id -32601 (str "Method not found: " method)))
      (catch Exception e
        (log/error e "Error handling MCP request" method)
        (make-error-response id -32603 (str "Internal error: " (.getMessage e)))))))

(defn handle-batch-request
  "Handle multiple JSON-RPC requests in a batch.
   Used by Claude for multi-turn conversations with tool use."
  [requests session-permissions]
  (mapv #(handle-request % session-permissions) requests))
