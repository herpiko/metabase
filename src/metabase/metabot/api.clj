(ns metabase.metabot.api
  "API endpoints for Metabot - AI chatbot for embedded views."
  (:require
   [metabase.api.common :as api]
   [metabase.api.macros :as api.macros]
   [metabase.api.routes.common :refer [+message-only-exceptions]]
   [metabase.api.util.handlers :as handlers]
   [metabase.embedding-rest.api.common :as api.embed.common]
   [metabase.metabot.claude :as metabot.claude]
   [metabase.metabot.conversation :as metabot.conversation]
   [metabase.metabot.permissions :as metabot.permissions]
   [metabase.request.core :as request]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.log :as log]
   [metabase.util.malli.schema :as ms]
   [throttle.core :as throttle]))

(set! *warn-on-reflection* true)

(comment keep-me)

;;; -------------------------------------------- Rate Limiting --------------------------------------------

(def ^:private metabot-throttlers
  "Throttlers for Metabot endpoints.
   - :ip-address limits requests per IP (default 150/minute)
   - :user-id limits requests per user (default 30/minute)"
  {:ip-address (throttle/make-throttler :ip-address
                                        :attempts-threshold 150
                                        :attempt-ttl-ms 60000)
   :user-id (throttle/make-throttler :user-id
                                     :attempts-threshold 30
                                     :attempt-ttl-ms 60000)})

;;; -------------------------------------------- Embed Token Endpoints --------------------------------------------

(api.macros/defendpoint :post "/embed/:token/chat"
  :- [:map
      [:content :string]
      [:conversation_id :string]
      [:tool_uses {:optional true} :int]
      [:duration_ms {:optional true} :int]]
  "Send a message to Metabot and get AI response.

   Requires a valid embed token for an embedded dashboard or question.
   Creates a new conversation if conversation_id not provided."
  [{:keys [token]} :- [:map [:token api.embed.common/EncodedToken]]
   body :- [:map
            [:message :string]
            [:conversation_id {:optional true} :string]]
   request]
  ;; Check that Metabot is available
  (metabot.permissions/check-metabot-enabled!)

  ;; Rate limiting
  (throttle/with-throttling [(metabot-throttlers :ip-address) (request/ip-address request)
                             (metabot-throttlers :user-id) (or api/*current-user-id* "anonymous")]

    ;; Build session permissions from token
    (let [session-permissions (metabot.permissions/build-session-permissions token)
          {:keys [message conversation_id]} body
          ;; Create new conversation if not provided
          conv-id (or conversation_id (metabot.claude/create-conversation))]

      (when-not (seq message)
        (throw (ex-info (tru "Message cannot be empty")
                        {:status-code 400})))

      ;; Send message to Claude and get response
      (let [response (metabot.claude/send-message conv-id message session-permissions)]
        response))))

(api.macros/defendpoint :get "/embed/:token/conversation/:conversation-id"
  :- [:map
      [:messages [:sequential [:map
                               [:role :keyword]
                               [:content :string]
                               [:timestamp :int]]]]]
  "Get conversation history for a specific conversation.

   Returns all messages in the conversation."
  [{:keys [token conversation-id]} :- [:map
                                       [:token api.embed.common/EncodedToken]
                                       [:conversation-id ms/NonBlankString]]
   _query-params
   request]
  ;; Check that Metabot is available
  (metabot.permissions/check-metabot-enabled!)

  ;; Rate limiting
  (throttle/with-throttling [(metabot-throttlers :ip-address) (request/ip-address request)]

    ;; Verify token is valid
    (let [_session-permissions (metabot.permissions/build-session-permissions token)
          messages (metabot.conversation/get-messages conversation-id)]

      {:messages messages})))

(api.macros/defendpoint :delete "/embed/:token/conversation/:conversation-id"
  :- [:map [:success :boolean]]
  "Clear/delete a conversation.

   Removes all messages from the conversation."
  [{:keys [token conversation-id]} :- [:map
                                       [:token api.embed.common/EncodedToken]
                                       [:conversation-id ms/NonBlankString]]
   _query-params
   request]
  ;; Check that Metabot is available
  (metabot.permissions/check-metabot-enabled!)

  ;; Rate limiting
  (throttle/with-throttling [(metabot-throttlers :ip-address) (request/ip-address request)]

    ;; Verify token is valid
    (let [_session-permissions (metabot.permissions/build-session-permissions token)]
      (metabot.conversation/clear-conversation conversation-id)
      {:success true})))

(api.macros/defendpoint :get "/embed/:token/status"
  :- [:map
      [:available :boolean]
      [:enabled :boolean]
      [:configured :boolean]]
  "Check if Metabot is available for this embedded view.

   Returns status information about Metabot availability."
  [{:keys [token]} :- [:map [:token api.embed.common/EncodedToken]]
   _query-params
   request]
  ;; Rate limiting (lighter for status checks)
  (throttle/with-throttling [(metabot-throttlers :ip-address) (request/ip-address request)]

    ;; Verify token is valid (but don't require Metabot to be enabled)
    (let [_session-permissions (try
                                 (metabot.permissions/build-session-permissions token)
                                 (catch Exception e
                                   (log/warn e "Invalid token in status check")
                                   nil))
          enabled (metabase.metabot.settings/metabot-enabled)
          configured (metabase.llm.settings/llm-anthropic-api-key-configured?)
          available (and enabled configured)]

      {:available available
       :enabled enabled
       :configured configured})))

;;; -------------------------------------------- Admin Endpoints --------------------------------------------

(api.macros/defendpoint :get "/stats"
  :- [:map
      [:conversation_count :int]]
  "Get Metabot statistics (admin only).

   Returns information about active conversations and usage."
  [_route-params _query-params]
  (api/check-superuser)

  {:conversation_count (metabot.conversation/conversation-count)})

;;; -------------------------------------------- Routes --------------------------------------------

(def ^{:arglists '([request respond raise])} routes
  "`/api/metabot` routes."
  (handlers/routes
   (api.macros/ns-handler *ns* +message-only-exceptions)))
