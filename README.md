# Viewfinder

A shader debugging mod for [Iris](https://irisshaders.dev/) on Fabric that exposes an MCP server and in-game commands for AI agents to inspect, test, and automate shader development workflows.

## Features

- **Shader reload and error list** - trigger shader reloads and capture compilation errors
- **Queued atomic workflows** - run up to 64 ordered actions without interleaving between MCP clients or agents
- **Shaderpack and config switching** - list installed packs, select one, reset or apply options, and reload
- **On-demand GPU profiles** - collect hierarchical per-pass timings over a fixed frame window without permanent query overhead
- **Pipeline and program inspection** - observe nested Iris passes and reflect linked program uniforms, UBOs, and SSBOs
- **RT / PT & compute shader diagnostics** - introspect compute workgroups, image units, memory barriers, and per-dispatch microsecond GPU timings
- **3D texture & voxel grid analysis** - probe and dump slices along X/Y/Z with OpenGL 4.5/3.0 subvolume readback, golden-ratio hash palette colorization, and occupancy/bounding-box integrity metrics
- **Structured SSBO introspection** - automatically reflect GLSL struct member names, types, offsets, and array strides via `GL_BUFFER_VARIABLE`, navigate arbitrary offsets, and scan for IEEE-754 NaNs/Infs
- **HDR radiance & false-color heatmaps** - export pure Java 32-bit Radiance `.hdr` (RGBE) files, exposure tone mapping, Turbo/Viridis traversal heatmaps, and signed vector remapping
- **Temporal controls & convergence profiling** - flush ping-pong history textures (`glClearTexImage`), freeze camera position and TAA subpixel jitter, and compute frame-to-frame RMSE convergence
- **Texture dumping** - inspect or export main/alternate colortex, depth, shadow, noise, and custom textures
- **SSBO buffer dumping** - dump shader storage buffer objects to disk for analysis
- **Screenshot scheduling** - schedule screenshots with configurable frame delay (for PT)
- **Patched shader listing** - find which patched shader errored programmatically by listing all and searching for "errored"
- **Interactive chat output** - copy paths, open dumped files, and insert follow-up commands from chat
- **Agent diagnostics snapshot** - one JSON payload with shaderpack, render, errors, metrics, textures, SSBOs, and suggested next actions
- **Bounded inspection probes** - inspect texture stats/pixels and SSBO byte previews before dumping large GPU resources
- **Pass output capture** - export framebuffer attachments at the end of a selected Iris pass
- **Shader mutation** - edit directory-pack sources or options and reload with structured diagnostics
- **Deterministic scenes** - position the authoritative singleplayer player, set time/weather, and freeze or step ticks
- **Client render settings** - query or change render distance and base FOV, with the server-limited effective distance reported

## RT / PT & Compute Shader Diagnostics

Modern Minecraft ray tracing (RT) and path tracing (PT) shaderpacks (such as *Photon*, *SEUS PTGI*, *KappaPT*, and *Rethinking Voxels*) operate on a compute-centric, volumetric, and temporal architecture. Viewfinder provides dedicated diagnostics to inspect these pipelines and pinpoint bugs that traditional raster debuggers miss.

### What Can Be Inspected

1. **Compute Programs & Workgroups**:
   - Workgroup dimensions $(X, Y, Z)$ and total invocations per workgroup (`GL_COMPUTE_WORK_GROUP_SIZE`) via `inspect_program` and `/viewfinder compute inspect <name>`.
   - Bound OpenGL image units (`glBindImageTexture`) and custom compute images (`customImages`) via `list_textures` and `inspect_texture`.
   - Compute pass output attachments and SSBO writes captured atomically with memory barrier synchronization (`glMemoryBarrier`).
   - Per-dispatch microsecond GPU timings via non-blocking timestamp queries (`glQueryCounter(GL_TIMESTAMP)`).

2. **3D Textures & Voxel Grids**:
   - Subvolume and 2D slice readback (`slice: int`, `axis: "Z" | "Y" | "X"`) via OpenGL 4.5 `glGetTextureSubImage` and GL 3.0 scratch FBO layer fallback, eliminating out-of-memory skips on large $256^3$ voxel volumes.
   - Deterministic golden-ratio hash palette colorization for integer voxel channels (`R32UI`, `RGBA16UI`), mapping discrete block IDs to distinct colors instead of destructive grayscale normalization.
   - Voxel grid integrity metrics: **Occupancy Ratio** (% non-empty voxels), **Active Bounding Box** ($[x_{min}, y_{min}, z_{min}] \to [x_{max}, y_{max}, z_{max}]$), and **Block ID Frequency Histogram**.

3. **Structured SSBOs & Acceleration Structures**:
   - Zero-schema struct reflection via OpenGL 4.3 `GL_BUFFER_VARIABLE` program interface queries (`glGetProgramInterfaceiv`, `glGetProgramResourceiv`), automatically reporting struct member names, types, byte offsets, and array strides directly from compiled shaders.
   - Arbitrary offset and length navigation (`offsetBytes`, `lengthBytes`) via `inspect_ssbo` to traverse deep BVH node trees, light lists, or ReSTIR reservoir arrays.
   - Whole-buffer bitwise scans across SSBOs to locate and count IEEE-754 `NaN`, `+Infinity`, `-Infinity`, and subnormal values.

4. **High Dynamic Range (HDR) Radiance & Heatmaps**:
   - Pure Java Greg Ward 32-bit Radiance `.hdr` (RGBE) export with scanline RLE compression, preserving extreme radiance values ($0.0$ to $10{,}000+$ lux) without 8-bit clipping.
   - Radiance exposure tone mapping previews ($1.0 - e^{-\text{radiance} \cdot \text{exposure}}$).
   - Signed vector remapping ($\frac{V + 1.0}{2.0} \times 255$) for normal buffers and screen motion vectors.
   - Perceptually uniform false-color heatmaps (*Turbo* and *Viridis*) for ray step counts, DDA iterations, and BVH node visit counts.
   - Binary NaN/Infinity highlight masks to pinpoint firefly pixel coordinates.

5. **Temporal Accumulation, Reprojection & Denoising**:
   - Ping-pong history buffer flushing (`/viewfinder history flush`) using `glClearTexImage` to reset `colortexN_alt` and custom history images to zero, isolating 1-SPP raw Monte Carlo noise from accumulated bias.
   - Deterministic transform freeze: `/viewfinder freeze camera` locks modelview/projection matrices, while `/viewfinder freeze jitter` cancels TAA/PT subpixel jitter.
   - Convergence profiler (`/viewfinder convergence [frames]`): computes frame-to-frame Root Mean Squared Error (RMSE) across multi-frame accumulation to measure denoiser (SVGF/A-trous) convergence rates.

6. **Driver Hardware Limits & OpenGL Error Trapping**:
   - Driver debug message interception via `glDebugMessageCallback` / KHR_debug piped directly into `ErrorCapture`, surfacing silent compute dispatch and out-of-bounds errors.
   - Hardware compute limits reported in `driverInfo`: `maxComputeWorkGroupInvocations`, `maxComputeSharedMemorySize`, `maxShaderStorageBlockSize`, `maxShaderStorageBufferBindings`, `maxComputeShaderStorageBlocks`, `maxImageUnits`, `maxComputeWorkGroupSize`, and `maxComputeWorkGroupCount`.

---

### What Bugs & Issues Can Be Detected

| Bug / Issue Category | Symptoms in Game | How to Detect with Viewfinder |
|---|---|---|
| **Voxelization Drift / Freeze** | Shadows or GI don't update when walking; voxels lag behind player | Inspect `/viewfinder voxel name <name>`: verify whether the `boundingBox` updates its world-coordinate offset as the player moves. |
| **Empty or Solid-Leak Voxel Grids** | Completely missing GI/shadows, or world engulfed in solid shadow | Run `/viewfinder voxel name <name>`: check `occupancyRatio`. $0.0$ indicates voxelization pass failed completely; $1.0$ indicates a leak or solid fill. |
| **Block ID Mapping Failures** | Modded or unexpected blocks have incorrect emissive, reflective, or transparency properties | Inspect voxel slices with `/viewfinder voxel slice <name> Z <layer>`. Golden-ratio hash palette colorizes IDs discretely. Check `topBlockIds` histogram to spot unmapped ID 0/air replacements. |
| **NaN / Infinity Firefly Contamination** | Blown-out bright pixels spreading across the screen or permanently poisoning history | Run `/viewfinder ssbo scan <index>` to count buffer NaNs. Dump textures with `mode: "nan_mask"` to locate exact $(X, Y)$ pixel coordinates of mathematical singularities. |
| **Ray Traversal Infinite Loops** | Severe framerate drops in specific biomes, angles, or cave entrances | Dump step-count textures using `mode: "heatmap"` and `colorRamp: "turbo"`. Highlights pixels reaching maximum traversal iterations in bright red. |
| **Driver Workgroup Overflow** | Compute shaders fail to dispatch; black screen on specific GPUs | Query `/viewfinder compute inspect <name>` and compare `invocationsPerWorkGroup` against `maxComputeWorkGroupInvocations` in `driverInfo`. |
| **Shared Memory Exhaustion** | Silent compile or dispatch failures on low-end or non-NVIDIA GPUs | Compare declared compute `shared` variables against `maxComputeSharedMemorySize` in `driverInfo`. |
| **SSBO Struct Padding Mismatches** | Scrambled light lists, corrupt BVH nodes, incorrect colors | Inspect `bufferVariables` in `inspect_program`: compare driver-reported struct field offsets and array strides against shader declarations (`std430` vs `std140`). |
| **TAA Ghosting vs Reprojection Error** | Smearing and trails behind moving blocks or entities | Run `/viewfinder freeze jitter true`: if smearing persists, the bug is in the velocity motion vector scaling or reprojection matrix; if it disappears, jitter phase is misaligned. |
| **Denoiser Convergence Stalling** | Noisy speckled image never cleans up even when standing still | Run `/viewfinder convergence 60`: if RMSE stabilizes at a high plateau instead of exponentially decaying towards zero, spatial/temporal weights are rejecting valid samples. |
| **History Buffer Poisoning** | Old lighting changes or ghost entities linger across scene resets | Execute `/viewfinder history flush` to reset accumulators to zero without reloading the entire shaderpack. |
| **HDR Radiance Clipping** | Lighting appears washed out, flat white, or details in sun/fire are lost | Dump textures using `format: "hdr"` and inspect raw floating-point radiance values in tev/Photoshop, or use `mode: "exposure"` with custom exposure values. |

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) for Minecraft 26.1, 26.2, or 26.3
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) and [Iris](https://modrinth.com/mod/iris) (which requires [Sodium](https://modrinth.com/mod/sodium))
3. Drop the Viewfinder `.jar` matching your Minecraft version into your `mods/` folder

## Development

Stonecutter builds all supported versions from the shared source tree:

```bash
./gradlew build
```

The release jars are written to `versions/26.1/build/libs/`, `versions/26.2/build/libs/`, and `versions/26.3/build/libs/`. Minecraft 26.3 is the default active version. Use `./gradlew "Set active project to 26.1"`, `./gradlew "Set active project to 26.2"`, or `./gradlew "Set active project to 26.3"` when editing version-dependent source.

## Usage

### In-game commands

All commands are registered under `/viewfinder`:

| Command | Description |
|---|---|
| `/viewfinder status` | Show current shaderpack name, error count, and MCP address |
| `/viewfinder snapshot` | Summarize the agent diagnostics snapshot |
| `/viewfinder reload` | Reload shaders and report any compilation errors |
| `/viewfinder errors` | List all captured shader errors |
| `/viewfinder errors all` | List the full captured error buffer |
| `/viewfinder errors clear` | Clear captured shader errors |
| `/viewfinder screenshot [frames]` | Take a screenshot (optionally delayed by N frames) |
| `/viewfinder screenshot result` | Show the path of the last screenshot |
| `/viewfinder metrics` | Display the last captured GPU timings |
| `/viewfinder metrics capture [frames]` | Profile the next fixed number of frames |
| `/viewfinder metrics reset` | Clear collected GPU timing samples |
| `/viewfinder ssbo list` | List active SSBO buffers |
| `/viewfinder ssbo inspect <index> [bytes] [offset]` | Preview SSBO bytes as hex, int, uint, and float values with optional offset |
| `/viewfinder ssbo scan <index>` | Scan SSBO buffer for IEEE-754 NaNs, Infs, and subnormals |
| `/viewfinder ssbo dump <index>` | Dump an SSBO buffer to `ssbo_dumps/` |
| `/viewfinder program list` | List all linked Iris shader programs |
| `/viewfinder program inspect <name>` | Reflect uniforms, UBOs, SSBOs, and compute workgroup parameters |
| `/viewfinder compute list` | List compute programs, workgroup dimensions, and invocation counts |
| `/viewfinder compute inspect <name>` | Inspect a specific compute shader program's workgroups and uniforms |
| `/viewfinder compute profile [frames]` | Profile per-dispatch compute shader GPU execution timings |
| `/viewfinder voxel name <name>` | Report voxel occupancy ratio, active bounding box, and block ID histogram |
| `/viewfinder voxel id <id>` | Report voxel diagnostics by OpenGL texture id |
| `/viewfinder voxel slice <name> <axis> <slice>` | Inspect a 2D slice of a 3D texture along X, Y, or Z |
| `/viewfinder texture list` | List available colortex, depth, shadow, and custom compute images |
| `/viewfinder texture inspect name <name> [samples]` | Probe texture metadata, samples, and channel statistics by name |
| `/viewfinder texture inspect id <id> [samples]` | Probe texture metadata, samples, and channel statistics by GL id |
| `/viewfinder texture sample name <name> <x> <y> [z]` | Read a specific texture pixel by name |
| `/viewfinder texture sample id <id> <x> <y> [z]` | Read a specific texture pixel by GL id |
| `/viewfinder texture dump name <name> [raw]` | Dump a texture by name (PNG, HDR RGBE, or raw) |
| `/viewfinder texture dump id <id> [raw]` | Dump a texture by GL id (PNG, HDR RGBE, or raw) |
| `/viewfinder history flush` | Flush ping-pong colortex history buffers and custom history images |
| `/viewfinder freeze status` | Show current camera transform and subpixel jitter freeze states |
| `/viewfinder freeze camera <true\|false>` | Freeze camera transform matrix to isolate static reprojection |
| `/viewfinder freeze jitter <true\|false>` | Freeze subpixel TAA/PT projection jitter |
| `/viewfinder freeze all <true\|false>` | Freeze both camera position and subpixel jitter |
| `/viewfinder convergence [frames]` | Measure multi-frame Monte Carlo RMSE convergence rate |
| `/viewfinder patched_shaders` | List Iris patched shader files |

Chat output uses clickable controls where Minecraft supports them: dumped file paths can be opened or copied, list output includes dump command suggestions, and captured errors include copy buttons for full messages and stack traces.

### MCP server

When the Minecraft client starts, Viewfinder automatically starts a Streamable HTTP MCP server at:

```text
http://127.0.0.1:7150/mcp
```

The server exists only while Minecraft is running with Viewfinder loaded. Run `/viewfinder status` in-game to confirm the address. If port `7150` is already in use, Viewfinder reports the startup failure in the Minecraft log.

> [!WARNING]
> The MCP server has no authentication. It is restricted to IPv4 loopback and rejects non-loopback `Host` and `Origin` values, but any process on your computer may still attempt to connect. Do not expose it through port forwarding, a reverse proxy, an SSH tunnel, container networking, or a bind address such as `0.0.0.0`.

> [!CAUTION]
> Viewfinder is not read-only. An MCP client can edit unpacked shaderpack sources, change shader options, reload shaders, capture screenshots and GPU data, and control a singleplayer scene. Connect only clients and agents you trust, and review tool calls that mutate files or game state.

There are no legacy REST or OpenAPI endpoints. MCP clients must use Streamable HTTP.

#### Add to Codex using the plugin

The plugin is the recommended Codex setup because it installs both the MCP connection and Viewfinder-specific agent instructions. It does not install the Minecraft mod or start the server.

1. Install Viewfinder in Minecraft and launch the game.
2. Clone this repository and open a terminal in its root directory.
3. Add the repository's local plugin marketplace and install Viewfinder:

   ```bash
   codex plugin marketplace add .
   codex plugin add viewfinder@viewfinder-local
   ```

4. Start a new Codex session so it loads the plugin and bundled MCP configuration.
5. Open `/plugins` in Codex to confirm that `viewfinder` is enabled, then ask Codex to get the Viewfinder diagnostics.

During plugin development, reinstall the plugin after changing its manifest so Codex refreshes its cached copy.

#### Add to Codex without the plugin

To register only the MCP server, without Viewfinder's plugin instructions, run:

```bash
codex mcp add viewfinder --url http://127.0.0.1:7150/mcp
```

Start a new Codex session after adding it. You can inspect or remove the registration with:

```bash
codex mcp list
codex mcp remove viewfinder
```

#### Add to another MCP client

Configure a Streamable HTTP server named `viewfinder` with the URL `http://127.0.0.1:7150/mcp`. Clients that use an `mcpServers` JSON object commonly accept:

```json
{
  "mcpServers": {
    "viewfinder": {
      "type": "http",
      "url": "http://127.0.0.1:7150/mcp"
    }
  }
}
```

The exact settings filename and schema depend on the client. Restart the MCP client after changing its configuration, and keep Minecraft running while using Viewfinder.

Tools:

| Tool | Description |
|---|---|
| `get_mcp_status` | Inspect the active request and bounded shared queue without waiting behind it |
| `run_actions` | Preflight and execute up to 64 ordered actions as one non-interleavable queued job |
| `get_diagnostics`, `clear_diagnostics`, `reload_shaders` | Read or reset diagnostics and reload Iris |
| `list_shaderpacks`, `switch_shaderpack` | Discover installed packs and switch pack/config |
| `set_shader_options`, `write_shader_source`, `write_shader_sources` | Mutate config/source and reload once; use the plural tool for multiple files |
| `inspect_program`, `dump_program_binary` | Reflect or export by preferred exact Iris name, or explicit GL id when unnamed |
| `inspect_texture`, `dump_texture` | Probe or export by preferred Iris name, or explicit GL id when unnamed |
| `inspect_ssbo`, `dump_ssbo` | Probe or export an explicit Iris SSBO binding |
| `capture_frame`, `capture_pass_outputs` | Capture a frame or selected pass attachments |
| `profile_frames` | Profile up to 1–600 frames with summaries by default and optional raw samples |
| `get_render_settings`, `set_render_settings` | Query or change client render distance in chunks and base FOV in degrees |
| `set_scene`, `control_ticks` | Control an integrated singleplayer server scene |

Every operation except `get_mcp_status` and reads of immutable capture files uses one global FIFO execution queue. One
active request and up to 32 waiting requests may share the running Minecraft instance; excess calls fail instead of
growing an unbounded queue. Successful queued results include a request ID, initial queue position, queue time, and
execution time. `get_mcp_status` deliberately bypasses the queue so another agent can inspect an active or waiting job.

Use `run_actions` whenever later steps depend on state established by earlier steps. Every nested action is validated
before action zero executes, preventing argument mistakes from causing partial mutations. Each action has a `type`
equal to an existing tool name and otherwise uses that tool's arguments. The action-only `wait_frames`, `list_programs`,
`list_textures`, and `list_ssbos` types provide bounded render warmup and lightweight resource discovery. Reload-capable tools return only
their complete fresh errors and parsed `compilerMessages` when present, so inspect that response directly instead of
appending `reload_shaders` or `get_diagnostics`. Use `get_diagnostics` only when the broader runtime snapshot is needed;
its metrics are summarized unless `includeSamples` is true.

Program and texture tools require exactly one selector. Prefer exact Iris `name` values because they survive reloads;
use `id` only for unnamed OpenGL objects. SSBO tools require an explicit `index`. Missing identifiers never default to
zero, and lookup failures include current valid names, indices, values, or observed passes where available.

Use `write_shader_sources` for multi-file edits. It validates every path before writing, writes all requested sources,
and performs at most one reload. This avoids repeated shader compilation and returns any resulting errors in the same response.

For deterministic scene setup, freeze server ticks before `set_scene` in the same job and resume them when finished:

```json
{
  "actions": [
    {
      "type": "switch_shaderpack",
      "name": "MyShaderpack.zip",
      "resetConfig": true,
      "config": { "SHADOW_QUALITY": "HIGH" }
    },
    { "type": "set_render_settings", "renderDistance": 12, "fov": 70 },
    { "type": "control_ticks", "action": "freeze" },
    { "type": "set_scene", "time": 6000, "weather": "clear" },
    { "type": "wait_frames", "frames": 32 },
    { "type": "capture_frame", "frames": 0 },
    { "type": "profile_frames", "frames": 120, "maxSeconds": 60 },
    { "type": "control_ticks", "action": "resume" }
  ]
}
```

No other MCP tool or mutable-resource read can run between those actions. This is isolation, not rollback: if an action
fails, later actions are skipped and earlier Minecraft or file mutations remain applied. Manual in-game actions are
outside the MCP queue. If a job fails after freezing ticks, resume them with `control_ticks` when appropriate.

The bounded job and ordered-action model is adapted from [Vibris capture control](https://github.com/Luna5ama/vibris/blob/main/docs/capture-control.md#mcp-tools), scaled to Viewfinder's single in-process Minecraft runtime.

`get_render_settings` returns `settings.renderDistance`, `settings.effectiveRenderDistance` (which may be capped by the
server), and `settings.fov`. The same values are included in diagnostics under `render.settings`. FOV is the base
Minecraft setting before sprinting, zoom, or other gameplay effects. `set_render_settings` accepts `renderDistance`
(2–32 chunks), `fov` (30–110 degrees), or both; omitted fields keep their current values. Values must also be supported
by the running client's options. Changes are saved to Minecraft options and work in singleplayer and multiplayer.
Use an ordered `wait_frames` action before capturing or profiling the changed view.

`dump_program_binary` is restricted to NVIDIA's proprietary OpenGL driver. Unsupported vendors are rejected before capture. Up to 16 MiB of the complete NVIDIA pseudo-assembly is returned inline as MCP text content and also retained at the returned capture URI/path; the result reports whether inlining occurred.

Resources:

- `viewfinder://shaderpack/manifest`
- `viewfinder://pipeline`
- `viewfinder://shaderpack/source/{path}`
- `viewfinder://patched-shader/{name}`
- `viewfinder://capture/{captureId}/{file}`

Capture tools write to `viewfinder_captures/<captureId>/` in the game directory. Viewfinder retains the newest 20 capture directories that it owns. Shader source writes are confined to `shaders/` and work only for unpacked directory shaderpacks. Scene and tick mutation is rejected outside singleplayer.
Capture resource reads are limited to 16 MiB; larger captures remain available at the filesystem path returned by the dump tool.
