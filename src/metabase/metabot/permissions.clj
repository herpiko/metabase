(ns metabase.metabot.permissions
  "Permission checking for Metabot. Validates that embedded session has access to requested resources."
  (:require
   [metabase.embedding.jwt :as embedding.jwt]
   [metabase.util.log :as log]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

;;; -------------------------------------------- Permission Extraction --------------------------------------------

(defn- get-dashboard-from-token
  "Extract dashboard ID from an embed token."
  [unsigned-token]
  (get-in unsigned-token [:resource :dashboard]))

(defn- get-question-from-token
  "Extract question (card) ID from an embed token."
  [unsigned-token]
  (get-in unsigned-token [:resource :question]))

(defn- get-accessible-database-ids
  "Get database IDs accessible from the embedded resource."
  [unsigned-token]
  (when-let [dashboard-id (get-dashboard-from-token unsigned-token)]
    (let [dashboard (t2/select-one :model/Dashboard :id dashboard-id)
          dashcards (t2/select :model/DashboardCard :dashboard_id dashboard-id)
          card-ids (keep :card_id dashcards)
          cards (when (seq card-ids)
                  (t2/select :model/Card :id [:in card-ids]))
          db-ids (set (keep :database_id cards))]
      db-ids)))

(defn- get-accessible-table-ids
  "Get table IDs accessible from the embedded resource."
  [unsigned-token]
  (when-let [dashboard-id (get-dashboard-from-token unsigned-token)]
    (let [dashcards (t2/select :model/DashboardCard :dashboard_id dashboard-id)
          card-ids (keep :card_id dashcards)
          cards (when (seq card-ids)
                  (t2/select [:model/Card :id :dataset_query :database_id] :id [:in card-ids]))
          ;; Extract table IDs from MBQL queries
          table-ids (set (keep (fn [card]
                                 (when-let [query (:dataset_query card)]
                                   (get-in query [:query :source-table])))
                               cards))]
      table-ids)))

;;; -------------------------------------------- Session Permissions --------------------------------------------

(defn build-session-permissions
  "Build a permission context from an embed token.
   Returns a map with accessible database and table IDs."
  [token-string]
  (try
    (let [unsigned-token (embedding.jwt/unsign token-string)
          dashboard-id (get-dashboard-from-token unsigned-token)
          question-id (get-question-from-token unsigned-token)]

      ;; Verify the resource exists and embedding is enabled
      (cond
        dashboard-id
        (let [dashboard (t2/select-one :model/Dashboard :id dashboard-id)]
          (when-not (:enable_embedding dashboard)
            (throw (ex-info "Embedding not enabled for dashboard" {:dashboard-id dashboard-id})))
          {:type :dashboard
           :resource-id dashboard-id
           :database-ids (get-accessible-database-ids unsigned-token)
           :table-ids (get-accessible-table-ids unsigned-token)
           :token unsigned-token})

        question-id
        (let [card (t2/select-one :model/Card :id question-id)]
          (when-not (:enable_embedding card)
            (throw (ex-info "Embedding not enabled for question" {:question-id question-id})))
          (let [table-id (get-in card [:dataset_query :query :source-table])
                db-id (:database_id card)]
            {:type :question
             :resource-id question-id
             :database-ids (when db-id #{db-id})
             :table-ids (when table-id #{table-id})
             :token unsigned-token}))

        :else
        (throw (ex-info "Invalid embed token: no dashboard or question resource" {}))))

    (catch Exception e
      (log/error e "Error building session permissions from token")
      (throw (ex-info "Invalid or expired embed token"
                      {:status-code 401}
                      e)))))

;;; -------------------------------------------- Permission Checks --------------------------------------------

(defn can-access-database?
  "Check if session has access to a database."
  [session-permissions database-id]
  (boolean (get-in session-permissions [:database-ids database-id])))

(defn can-access-table?
  "Check if session has access to a table."
  [session-permissions table-id]
  (boolean (get-in session-permissions [:table-ids table-id])))

(defn check-metabot-enabled!
  "Verify that Metabot is enabled and properly configured.
   Throws if not available."
  []
  (require 'metabase.metabot.settings)
  (let [metabot-available? (requiring-resolve 'metabase.metabot.settings/metabot-available?)]
    (when-not (metabot-available?)
      (throw (ex-info "Metabot is not available. Check that it is enabled and Claude API key is configured."
                      {:status-code 503})))))
