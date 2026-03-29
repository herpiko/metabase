(ns metabase.metabot.init
  "Initialization for Metabot subsystem."
  (:require
   [metabase.mcp.init :as mcp.init]
   [metabase.metabot.conversation :as metabot.conversation]
   [metabase.util.log :as log]))

(defn init!
  "Initialize Metabot subsystem. Called during Metabase startup."
  []
  (log/info "Initializing Metabot...")

  ;; Initialize MCP server
  (mcp.init/init!)

  ;; Initialize conversation management
  (metabot.conversation/init!)

  (log/info "Metabot initialization complete"))
