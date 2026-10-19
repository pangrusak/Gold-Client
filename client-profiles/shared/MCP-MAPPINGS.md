# MCP SRG member mappings

The Eaglercraft client exposes MCP-named Java members (`getMinecraft`,
`theWorld`). Forge mods compiled against a mapped 1.12.2 development
environment reference SRG names (`func_71410_x`, `field_71441_e`). The
EaglerForge bridge needs a deterministic SRG-to-MCP table to resolve those
members.

## Exact input

- Artifact: `de.oceanlabs.mcp:mcp_stable:39-1.12`
- Download:
  `https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_stable/39-1.12/mcp_stable-39-1.12.zip`
- ZIP SHA-256:
  `13a31f28c11f8f395ffe7e8563ade459f5a0ee46493abbbde3ce6e9493ac4152`
- `methods.csv` SHA-256:
  `d815643a65c3b769c5a859b9142d0537d0206a3387b2ac40deff87ca150db8c5`
- `fields.csv` SHA-256:
  `f83618fe93f51872d62734d049b45ccacb7bf9fa45222b1f1d9030d90564d76c`

## Generated artifact

`mcp-1.12.2-members.json.gz` is a gzip-compressed JSON object mapping each SRG
member name to its MCP name (19,659 entries). It is generated from the two CSV
files and contains no class-name or descriptor mappings.

```bash
node build-mcp-member-map.cjs methods.csv fields.csv mcp-1.12.2-members.json.gz
```

Generated SHA-256:
`02398eeb091455ab55152d77f216210db760014e239109e7b9457b4ea1387cd8`.

This table only renames members. It does not prove that a member exists in the
supplied client, that overload selection is correct, or that the mapped API is
semantically compatible.
