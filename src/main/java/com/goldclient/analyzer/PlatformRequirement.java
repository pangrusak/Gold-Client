package com.goldclient.analyzer;

/** Platform services that a translated mod may need from its target runtime. */
public enum PlatformRequirement {
  FORGE_PLATFORM_CONTEXT,
  FORGE_EVENT_BUS,
  FORGE_CLIENT_EVENT_BUS,
  FORGE_MOD_METADATA,
  FORGE_CONFIG_DIRECTORY
}
