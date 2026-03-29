(ns metabase.metabot.claude
  "Claude API client for Metabot with MCP (Model Context Protocol) integration.
   Handles multi-turn conversations with tool use."
  (:require
   [clj-http.client :as http]
   [metabase.llm.settings :as llm.settings]
   [metabase.mcp.tools :as mcp.tools]
   [metabase.metabot.conversation :as metabot.conversation]
   [metabase.util :as u]
   [metabase.util.json :as json]
   [metabase.util.log :as log])
  (:import
   (com.fasterxml.jackson.core JsonParseException)))

(set! *warn-on-reflection* true)

;;; -------------------------------------------- System Prompt --------------------------------------------

(def ^:private metabot-system-prompt
  "You are Metabot, an AI assistant embedded in Metabase dashboards and questions.
Your role is to help users understand and explore their data using natural language.

You have access to the following capabilities via MCP tools:
- Search for tables by name
- Get detailed table schemas with column information
- Execute MBQL queries to fetch data
- Create visualizations from query results
- List saved questions (existing queries)

When helping users:
1. Start by understanding what data they want to explore
2. Use search_tables to find relevant tables
3. Use get_table_schema to understand the structure
4. Use execute_query to fetch the data they need
5. Use create_visualization to show results visually when appropriate

Always explain what you're doing and why. Be concise but informative.
If you can't access certain data due to permissions, explain that clearly.

Remember: You only have access to data that's already part of this embedded view.
You cannot access data from other dashboards or questions.")

;;; -------------------------------------------- API Communication --------------------------------------------

(defn- build-request-headers
  "Build headers for Anthropic API request."
  [api-key]
  {"x-api-key" api-key
   "anthropic-version" (llm.settings/llm-anthropic-api-version)
   "content-type" "application/json"})

(defn- build-request-body
  "Build the request body for Anthropic messages API with tools."
  [{:keys [model messages tools]}]
  {:model model
   :max_tokens (llm.settings/llm-max-tokens)
   :system metabot-system-prompt
   :messages messages
   :tools tools})

(defn- handle-api-error
  "Handle HTTP errors from Anthropic API."
  [exception]
  (if-let [response-body (some-> exception ex-data :body)]
    (let [parsed (try
                   (json/decode response-body)
                   (catch JsonParseException _
                     {:error {:message response-body}}))]
      (throw (ex-info (or (-> parsed :error :message)
                          "Claude API request failed")
                      {:type :anthropic-api-error
                       :status (some-> exception ex-data :status)
                       :body parsed}
                      exception)))
    (throw exception)))

(defn- get-api-key-or-throw
  "Gets Anthropic API key from settings or throws an error."
  []
  (or (llm.settings/llm-anthropic-api-key)
      (throw (ex-info "Claude API key not configured"
                      {:status-code 503}))))

;;; -------------------------------------------- Tool Use Loop --------------------------------------------

(defn- process-tool-use
  "Process tool_use content blocks from Claude's response.
   Executes the tools and returns tool results."
  [content session-permissions]
  (let [tool-uses (filter #(= "tool_use" (:type %)) content)]
    (mapv (fn [tool-use]
            (let [tool-name (:name tool-use)
                  tool-input (:input tool-use)
                  tool-id (:id tool-use)]
              (log/debug "Executing tool:" tool-name "with input:" tool-input)
              (let [result (mcp.tools/execute-tool tool-name tool-input session-permissions)]
                {:type "tool_result"
                 :tool_use_id tool-id
                 :content (if (:error result)
                            (str "Error: " (:error result))
                            (json/encode (:result result)))})))
          tool-uses)))

(defn- extract-text-content
  "Extract text content from Claude's response."
  [content]
  (->> content
       (filter #(= "text" (:type %)))
       (map :text)
       (apply str)))

(defn- make-api-request
  "Make a request to Claude API."
  [messages tools]
  (let [url (str (llm.settings/llm-anthropic-api-url) "/v1/messages")
        model (llm.settings/llm-anthropic-model)
        request-body (build-request-body {:model model
                                          :messages messages
                                          :tools tools})]
    (try
      (let [response (http/post url
                                {:headers (build-request-headers (get-api-key-or-throw))
                                 :body (json/encode request-body)
                                 :as :json
                                 :content-type :json
                                 :socket-timeout (llm.settings/llm-request-timeout-ms)
                                 :connection-timeout (llm.settings/llm-connection-timeout-ms)})]
        (:body response))
      (catch Exception e
        (handle-api-error e)))))

(defn- chat-with-tools
  "Have a conversation with Claude that may involve multiple tool uses.
   Handles the agentic loop: message -> tool use -> tool result -> final response."
  [messages session-permissions]
  (let [tools (mcp.tools/get-tool-definitions)
        max-iterations 10] ; Prevent infinite loops
    (loop [current-messages messages
           iteration 0]
      (when (>= iteration max-iterations)
        (throw (ex-info "Too many tool iterations" {:max-iterations max-iterations})))

      (let [response (make-api-request current-messages tools)
            {:keys [content stop_reason]} response]

        (cond
          ;; Claude wants to use tools
          (= stop_reason "tool_use")
          (let [tool-results (process-tool-use content session-permissions)
                ;; Add assistant's response with tool_use to conversation
                messages-with-assistant (conj current-messages
                                              {:role "assistant"
                                               :content content})
                ;; Add tool results as user message
                messages-with-results (conj messages-with-assistant
                                            {:role "user"
                                             :content tool-results})]
            ;; Continue the loop with tool results
            (recur messages-with-results (inc iteration)))

          ;; Claude is done (end_turn or other stop reason)
          :else
          {:content (extract-text-content content)
           :stop_reason stop_reason
           :tool_uses iteration})))))

;;; -------------------------------------------- Public API --------------------------------------------

(defn send-message
  "Send a message to Claude and get a response.
   Handles multi-turn conversations with tool use via MCP.

   Parameters:
   - conversation-id: ID of the conversation
   - message: User's message text
   - session-permissions: Permission context from embed token

   Returns:
   - Map with :content (assistant's response text) and metadata"
  [conversation-id message session-permissions]
  (try
    (let [start-time (u/start-timer)
          ;; Add user message to conversation
          _ (metabot.conversation/add-user-message conversation-id message)

          ;; Get conversation history and format for Claude
          history (metabot.conversation/get-messages conversation-id)
          claude-messages (metabot.conversation/format-messages-for-claude history)

          ;; Send to Claude with tool support
          {:keys [content tool_uses stop_reason]} (chat-with-tools claude-messages session-permissions)

          ;; Add assistant response to conversation
          _ (metabot.conversation/add-assistant-message conversation-id content)

          duration-ms (u/since-ms start-time)]

      (log/debug "Metabot response generated in" duration-ms "ms with" tool_uses "tool uses")

      {:content content
       :conversation_id conversation-id
       :tool_uses tool_uses
       :duration_ms duration-ms})

    (catch Exception e
      (log/error e "Error in Metabot chat")
      (throw (ex-info "Failed to process message with Claude"
                      {:status-code 500}
                      e)))))

(defn create-conversation
  "Create a new conversation and return its ID."
  []
  (metabot.conversation/create-conversation))
