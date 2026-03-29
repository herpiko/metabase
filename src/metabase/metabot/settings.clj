(ns metabase.metabot.settings
  "Settings for Metabot - AI chatbot for embedded views."
  (:require
   [metabase.llm.settings :as llm.settings]
   [metabase.settings.core :as setting :refer [defsetting]]))

(defsetting metabot-enabled
  "Enable Metabot AI chatbot in embedded views. Requires Anthropic API key to be configured."
  :type :boolean
  :default false
  :visibility :settings-manager
  :export? false
  :doc false)

(defsetting metabot-max-conversation-length
  "Maximum number of messages to keep in a Metabot conversation."
  :type :integer
  :default 50
  :visibility :settings-manager
  :export? false
  :doc false)

(defsetting metabot-conversation-ttl-minutes
  "Time in minutes before a Metabot conversation expires and is removed from memory."
  :type :integer
  :default 60
  :visibility :settings-manager
  :export? false
  :doc false)

(defsetting metabot-max-query-results
  "Maximum number of rows to return from Metabot query executions."
  :type :integer
  :default 2000
  :visibility :settings-manager
  :export? false
  :doc false)

(defsetting metabot-enable-visualization-creation
  "Allow Metabot to create visualizations from query results."
  :type :boolean
  :default true
  :visibility :settings-manager
  :export? false
  :doc false)

(defn metabot-available?
  "Check if Metabot is available (enabled and LLM is configured)."
  []
  (and (metabot-enabled)
       (llm.settings/llm-anthropic-api-key-configured?)))
