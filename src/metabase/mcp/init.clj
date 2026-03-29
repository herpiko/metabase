(ns metabase.mcp.init
  "Initialization for MCP (Model Context Protocol) server."
  (:require
   [metabase.util.log :as log]))

(defn init!
  "Initialize MCP server. Called during Metabot startup."
  []
  (log/info "Initializing MCP server...")
  ;; MCP server is stateless and doesn't need initialization
  ;; Tools are registered via mcp.tools/get-tool-definitions
  (log/info "MCP server initialized"))
