<!-- pico-cli:plugin-context:pico-spatial-agentic-tools:start -->
## Plugin Context

### Spatial Accessibility Runtime Fast Path

For gameplay or a business goal, load the `spatial-accessibility` Skill directly.
Reject requests requiring real-time reactions before code, device queries or dispatch.
Unknown scenes fail closed. A supplied source-proven static initial state uses one goal
call without an extra setup query; cosmetic NPC movement alone does not reject the task.

For a clear single action, when project instructions already declare the CLI entry, device,
package, target and action arguments, use this fast path:
- execute exactly one declared `interact act` command, one `interact route` command for a
  Contract-declared cross-surface target, or one `interact batch` command for multiple
  ordered actions;
- do not read additional plugin context, invoke a Skill, inspect source, query first, call
  `--help`, or repeat a performed mutation;
- use `--action <string-key>` and `--arguments '<json-object>'`; never use `--args`,
  `--key`, or put JSON after `--action`;
- append `--refresh never --compact --format json` for local transform actions;
- return dispatch results separately from application goal completion; `performed` is not
  completion evidence.

Exact single-action shape:

```bash
pico-cli interact act --device <serial> --package <package> \
  --semantic-id <semantic-id> --action <string-key> \
  --arguments '<json-object>' --refresh never --compact --format json
```

Exact cross-surface shape:

```bash
pico-cli interact route --device <serial> --project <app-project> \
  --component <contract-id> --action <string-key> \
  --arguments '<json-object>' --compact --format json
```

For development, authoring, audit, unknown targets or arguments, ambiguity, or runtime failure, read `./PICO-SPATIAL-AGENTIC-TOOLS.AGENTS.md` for the full PICO Spatial plugin guidance.
<!-- pico-cli:plugin-context:pico-spatial-agentic-tools:end -->
