(ns metabase.metabot.conversation
  "In-memory conversation state management for Metabot.
   Stores conversation history with TTL for privacy and cleanup."
  (:require
   [clojure.core.cache.wrapped :as cache]
   [metabase.metabot.settings :as metabot.settings]
   [metabase.util.log :as log]))

(set! *warn-on-reflection* true)

;;; -------------------------------------------- Conversation Storage --------------------------------------------

(defonce ^:private conversations
  "In-memory cache of conversations with TTL.
   Key: conversation-id (UUID string)
   Value: {:messages [...], :created-at timestamp, :updated-at timestamp}"
  (atom nil))

(defn init!
  "Initialize conversation storage."
  []
  (log/info "Initializing conversation storage...")
  (reset! conversations (cache/ttl-cache-factory {} :ttl (* (metabot.settings/metabot-conversation-ttl-minutes) 60 1000)))
  (log/info "Conversation storage initialized"))

;;; -------------------------------------------- Conversation Management --------------------------------------------

(defn create-conversation
  "Create a new conversation. Returns the conversation ID."
  []
  (let [conversation-id (str (random-uuid))
        now (System/currentTimeMillis)]
    (cache/through-cache @conversations
                         conversation-id
                         (fn [_] {:messages []
                                  :created-at now
                                  :updated-at now}))
    conversation-id))

(defn add-message
  "Add a message to a conversation.
   Message should be a map with :role and :content keys."
  [conversation-id message]
  (let [now (System/currentTimeMillis)
        max-length (metabot.settings/metabot-max-conversation-length)]
    (cache/through-cache @conversations
                         conversation-id
                         (fn [conv]
                           (let [messages (or (:messages conv) [])
                                 new-messages (conj messages message)
                                ;; Trim to max length, keeping most recent messages
                                 trimmed-messages (if (> (count new-messages) max-length)
                                                    (vec (take-last max-length new-messages))
                                                    new-messages)]
                             {:messages trimmed-messages
                              :created-at (or (:created-at conv) now)
                              :updated-at now})))))

(defn get-conversation
  "Get a conversation by ID. Returns nil if not found or expired."
  [conversation-id]
  (cache/lookup @conversations conversation-id))

(defn get-messages
  "Get all messages from a conversation. Returns empty vector if conversation not found."
  [conversation-id]
  (or (:messages (get-conversation conversation-id)) []))

(defn clear-conversation
  "Clear a conversation by ID."
  [conversation-id]
  (cache/evict @conversations conversation-id))

(defn conversation-count
  "Get the total number of active conversations."
  []
  (count (cache/snapshot @conversations)))

;;; -------------------------------------------- Conversation History Formatting --------------------------------------------

(defn format-messages-for-claude
  "Format conversation messages for Claude API.
   Filters out system messages and ensures proper role alternation."
  [messages]
  (let [;; Filter to user and assistant messages only
        chat-messages (filter #(#{:user :assistant} (:role %)) messages)]
    (mapv (fn [msg]
            {:role (name (:role msg))
             :content (:content msg)})
          chat-messages)))

(defn add-user-message
  "Add a user message to the conversation."
  [conversation-id content]
  (add-message conversation-id {:role :user
                                :content content
                                :timestamp (System/currentTimeMillis)}))

(defn add-assistant-message
  "Add an assistant message to the conversation."
  [conversation-id content]
  (add-message conversation-id {:role :assistant
                                :content content
                                :timestamp (System/currentTimeMillis)}))

(defn add-tool-use
  "Add a tool use message to the conversation for tracking."
  [conversation-id tool-name arguments result]
  (add-message conversation-id {:role :tool
                                :tool tool-name
                                :arguments arguments
                                :result result
                                :timestamp (System/currentTimeMillis)}))
