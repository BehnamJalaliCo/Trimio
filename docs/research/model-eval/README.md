# Persian director eval (task 8b.8)

A repeatable check of a director model on the jobs Trimio gives it, in Persian: the app's own
understanding calls (same prompts, same GBNF grammar, same chat layout, same temperature), scored
against the workshop scenarios, plus two small public Persian sets. Nothing here edits the repo.

## Files
| file | what |
|---|---|
| `scenarios.py` | Reads `engine/autopilot/.../workshop/Scenarios.kt` (read-only) → `scenarios.json`: heard words, truth, lines, marked recogniser errors `{clean\|heard}`, CTA, domain. |
| `director.py` | Reads the prompts from `Understander.kt`/`Understanding.kt` at run time; line-for-line port of `JsonSchemaGrammar.kt`; ChatML (Qwen no-think prefix) and Gemma 4 layouts from `ChatTemplates.kt`. |
| `gold_shows.json` | Hand gold for 18 Persian scenarios: cue phrase → acceptable shows, hook phrases (synthetic, from the briefs). |
| `run_eval.py` | Runs global + groups-of-3 line calls against llama-server `/completion` and scores. |
| `public_eval.py` | `fetch` (HF datasets-server, local cache in `data/`, never commit) and `run`: Global-MMLU fa (Apache-2.0) and ParsiNLU NLI (CC-BY-NC-SA, internal only), grammar-constrained answers. |
| `results/` | JSON per run (summary + every reply). |

## Metrics
`json` (reply parsed and stopped on its own / calls), `domain`, `cta_action`, `cta_keyword`, `hook`,
`show` (gold cues, planner-equivalent shows count), `spell_fix` (marked recogniser errors whose clean
form is in the line's `fixed`), `spell_kept_wrong`, `cer_fixed` vs `cer_heard` (rewrite damage: a
model worse than the heard text is harmful), `fa_titles` (on-screen titles in Persian script; Latin only
for names in the script or brief), prompt/gen tokens and tok/s, wall time. Speeds on this shared
4-core container are not device speeds; use `llama-bench` on the target for those.

## Run
```
python3 -I scenarios.py <repo>/engine/autopilot/src/jvmTest/kotlin/io/trimio/engine/autopilot/workshop/Scenarios.kt scenarios.json
llama-server -m MODEL.gguf -c 8192 -t 1 -tb 2 --cache-ram 0 --port 8088 -np 1   # built from native/third_party/llama.cpp
# On a shared/overloaded host keep -t low: here -t 4 gave 0.08 tok/s, -t 1 gave 3.9 tok/s (spin-wait under load).
# --cache-ram 0: the default host prompt cache grew to 4.3 GB RSS and the server was OOM-killed.
python3 -I run_eval.py --format qwen|gemma4|chatml --model-name NAME --only fa- [--ids a,b] [--seeds 1,2] --out results/NAME.json
python3 -I public_eval.py fetch --n 100 && python3 -I public_eval.py run --format qwen --model-name NAME --out results/NAME-public.json
```
Pass bar proposed for a phone director: json ≥ 0.98, show ≥ 0.75, spell_fix ≥ 0.6 with
cer_fixed < cer_heard, fa_titles ≥ 0.95, cta_action ≥ 0.9.

## Wiring into the repo as an optional Gradle task (not done here)
1. Kotlin, same JVM path as the app (preferred for the gate): add
   `engine/autopilot/src/jvmTest/.../DirectorEvalTest.kt`, modelled on `ModelEvalTest` (it already has
   `JsonCounter` and the family → ChatFormat/DirectorProfile mapping). Iterate `Workshop.scenarios()`
   filtered by `language == "fa"`, run `Understander(counter).understand(...)`, and score with the rules
   above using the real `Scenario.truth`/`errors`/`ask`/`domain` (no regex parsing). Move
   `gold_shows.json` to `src/jvmTest/resources/eval/director-gold.json`. Return early unless
   `-Peval.llm=…` is set, exactly like `ModelEvalTest`, so CI stays green and fast.
2. Gradle: in `engine/autopilot/build.gradle.kts` register
   `tasks.register<Test>("directorEval") { group = "verification"; useJUnitPlatform(); filter.includeTestsMatching("*DirectorEvalTest*"); testClassesDirs/classpath from jvmTest; systemProperties eval.* passthrough; outputs.upToDateWhen { false } }`
   and keep it out of `check`. Write `build/eval/director-<model>.json` and one `RESULT` line.
3. Public sets: keep `public_eval.py` as a script under `tools/eval/` (Python, opt-in) with the cache in
   `build/`; never commit the NC/ND data. Global-MMLU fa may be committed as a 100-item sample (Apache-2.0)
   if a pure-JVM run is wanted.
4. Baseline: store per-model summaries in `src/jvmTest/resources/eval/director-baseline.json`;
   the task fails only when a metric drops below that model's baseline (same rule as the golden archive).

## Results so far (2026-10-12, this container: shared 4-vCPU Xeon, llama.cpp baef3ed, CPU only, -t 1)
Qwen3.5-2B-Q4_K_M (unsloth, sha256 aaf42c8b…9223 verified), 4 Persian scenarios
(fa-medical-vitamin-d, fa-crypto-bitcoin, fa-testimonial-ielts, fa-flash-sale-15s), seed 1, 18 calls:
json 18/18 · domain 0.75 · cta_action 1.00 · cta_keyword 0.67 («بیت» → "BET") · hook 0.25 · show 0/13 gold cues
(almost all "headline") · spell_fix 1/21, kept_wrong 0.86 · cer_fixed 0.101 vs cer_heard 0.029 (rewrites make text worse)
· fa_titles 0.55 (English titles such as "Nearly 80% of Iranians" on a Persian video) · 14.2k prompt + 5.5k generated tokens,
47 prompt tok/s, 4.0 gen tok/s, 28 min wall (host-bound, not a device number).
Public (n=50 each): Global-MMLU fa 0.40 (chance 0.25), ParsiNLU NLI 0.38 (chance ≈0.33), json 100/100.
Grammar cost (llama-server, same prompt, 32 tokens): 3.89 tok/s without grammar, 3.99 with the app grammar.
Not run here (disk 2.0 GB free, memory cgroup OOM-killed a 4.3 GB server once): Gemma 4 E2B/E4B, Qwen3.5-4B/9B.

The two public Persian datasets (Global-MMLU fa, ParsiNLU NLI) are not kept in the repo; `public_eval.py` downloads them.
