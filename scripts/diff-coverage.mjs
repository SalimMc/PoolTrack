#!/usr/bin/env node
// Fails when the branch coverage of the lines a pull request adds or changes is below a threshold.
//
// Usage: node scripts/diff-coverage.mjs --base origin/main [--report <jacoco xml>] [--threshold 90]
//
// SonarCloud's gate on new code cannot be customised on the free plan, so this is the check for
// "new code is covered at 90% of its branches". It reads the JaCoCo XML produced by
// `./gradlew jacocoTestReport` and only looks at lines with branches: a changed line without
// branches, a file JaCoCo does not report (tests, docs, build files) and a PR with no Kotlin
// changes all pass. Nothing is excluded from the count, generated Compose branches included.

import { execFileSync } from "node:child_process"
import { existsSync, readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"

export const DEFAULT_REPORT = "app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml"
export const DEFAULT_THRESHOLD = 90
const SOURCE_ROOT = "app/src/main/java/"

/** Lines added or changed per file, from the output of `git diff -U0`: path -> sorted line numbers. */
export function parseChangedLines(diff) {
  const changed = new Map()
  let current = null
  for (const line of diff.split("\n")) {
    if (line.startsWith("+++ ")) {
      const target = line.slice(4).trim()
      // "/dev/null" is a deleted file: it has no added lines.
      current = target.startsWith("b/") ? target.slice(2) : null
      continue
    }
    const hunk = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@/.exec(line)
    if (hunk && current) {
      const start = Number(hunk[1])
      const count = hunk[2] === undefined ? 1 : Number(hunk[2])
      const lines = changed.get(current) ?? []
      for (let i = 0; i < count; i++) lines.push(start + i)
      changed.set(current, lines)
    }
  }
  return changed
}

/** Branch counters per source line, from a JaCoCo XML report: path -> Map(line -> {missed, covered}). */
export function parseReport(xml) {
  const files = new Map()
  const packageRe = /<package name="([^"]*)">([\s\S]*?)<\/package>/g
  for (const pkg of xml.matchAll(packageRe)) {
    const sourceRe = /<sourcefile name="([^"]*)">([\s\S]*?)<\/sourcefile>/g
    for (const source of pkg[2].matchAll(sourceRe)) {
      const lines = new Map()
      for (const tag of source[2].matchAll(/<line\b[^>]*>/g)) {
        const nr = /\bnr="(\d+)"/.exec(tag[0])
        const missed = /\bmb="(\d+)"/.exec(tag[0])
        const covered = /\bcb="(\d+)"/.exec(tag[0])
        if (nr && missed && covered) {
          lines.set(Number(nr[1]), { missed: Number(missed[1]), covered: Number(covered[1]) })
        }
      }
      files.set(`${SOURCE_ROOT}${pkg[1]}/${source[1]}`, lines)
    }
  }
  return files
}

/** Covered and total branches over the changed lines, and the lines that miss some. */
export function computeDiffCoverage(changed, report) {
  let covered = 0
  let total = 0
  const misses = []
  for (const [path, changedLines] of changed) {
    const reported = report.get(path)
    if (!reported) continue
    const missing = []
    for (const nr of changedLines) {
      const counters = reported.get(nr)
      if (!counters) continue
      const branches = counters.missed + counters.covered
      covered += counters.covered
      total += branches
      if (counters.missed > 0) missing.push({ line: nr, missed: counters.missed, branches })
    }
    if (missing.length > 0) misses.push({ path, lines: missing })
  }
  return { covered, total, percent: total === 0 ? 100 : (100 * covered) / total, misses }
}

export function formatResult(result, threshold) {
  const out = []
  if (result.total === 0) {
    out.push("New code has no branches to cover: nothing to check.")
    return { ok: true, text: out.join("\n") }
  }
  const percent = result.percent.toFixed(1)
  const ok = result.percent >= threshold
  out.push(`Branch coverage of new code: ${percent}% (${result.covered}/${result.total} branches).`)
  if (result.misses.length > 0) {
    out.push("Changed lines with uncovered branches:")
    for (const { path, lines } of result.misses) {
      for (const { line, missed, branches } of lines) {
        out.push(`  ${path}:${line} (${missed} of ${branches} branches missed)`)
      }
    }
  }
  if (!ok) out.push(`Below the ${threshold}% required: add tests for the branches above.`)
  return { ok, text: out.join("\n") }
}

export function parseArgs(argv) {
  const options = { base: null, report: DEFAULT_REPORT, threshold: DEFAULT_THRESHOLD }
  for (let i = 0; i < argv.length; i += 2) {
    const value = argv[i + 1]
    if (value === undefined) throw new Error(`Missing value for ${argv[i]}`)
    if (argv[i] === "--base") options.base = value
    else if (argv[i] === "--report") options.report = value
    else if (argv[i] === "--threshold") options.threshold = Number(value)
    else throw new Error(`Unknown option ${argv[i]}`)
  }
  if (!options.base) throw new Error("--base <git ref> is required")
  if (!Number.isFinite(options.threshold)) throw new Error("--threshold must be a number")
  return options
}

function main() {
  const options = parseArgs(process.argv.slice(2))
  const diff = execFileSync(
    "git",
    ["-c", "core.quotepath=false", "diff", "-U0", "--no-color", `${options.base}...HEAD`, "--", "*.kt"],
    { encoding: "utf8", maxBuffer: 256 * 1024 * 1024 },
  )
  const changed = parseChangedLines(diff)
  const kotlinMain = [...changed.keys()].filter((path) => path.startsWith(SOURCE_ROOT))
  if (kotlinMain.length === 0) {
    console.log("No Kotlin source changes: nothing to check.")
    return 0
  }
  if (!existsSync(options.report)) {
    console.error(`Coverage report not found: ${options.report}. Run ./gradlew jacocoTestReport.`)
    return 1
  }
  const report = parseReport(readFileSync(options.report, "utf8"))
  const { ok, text } = formatResult(computeDiffCoverage(changed, report), options.threshold)
  console.log(text)
  return ok ? 0 : 1
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = main()
  } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  }
}
