import assert from "node:assert/strict"
import { execFileSync } from "node:child_process"
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { test } from "node:test"
import {
  computeDiffCoverage,
  formatResult,
  parseArgs,
  parseChangedLines,
  parseReport,
  readDiff,
  run,
} from "./diff-coverage.mjs"

const DIFF = `diff --git a/app/src/main/java/com/x/A.kt b/app/src/main/java/com/x/A.kt
index 111..222 100644
--- a/app/src/main/java/com/x/A.kt
+++ b/app/src/main/java/com/x/A.kt
@@ -3 +3,2 @@ fun a()
-old
+new
+newer
@@ -10,2 +11,0 @@ fun b()
-gone
-gone too
@@ -20,0 +20,3 @@ fun c()
+x
+y
+z
@@ -40 +42 @@ fun d()
-a
+b
diff --git a/app/src/main/java/com/x/Old.kt b/app/src/main/java/com/x/Old.kt
deleted file mode 100644
--- a/app/src/main/java/com/x/Old.kt
+++ /dev/null
@@ -1,2 +0,0 @@
-a
-b
`

const REPORT = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
<report name="app">
  <package name="com/x">
    <class name="com/x/A"><method name="a" desc="()V" line="3"><counter type="BRANCH" missed="1" covered="1"/></method></class>
    <sourcefile name="A.kt">
      <line nr="3" mi="0" ci="2" mb="0" cb="0"/>
      <line nr="4" mi="0" ci="2" mb="1" cb="1"/>
      <line nr="20" mi="0" ci="2" mb="0" cb="2"/>
      <line nr="21" mi="1" ci="0" mb="2" cb="0"/>
      <line nr="42" mi="0" ci="1" mb="0" cb="4"/>
      <line nr="99" mi="0" ci="1" mb="3" cb="0"/>
    </sourcefile>
  </package>
  <package name="com/y">
    <sourcefile name="B.kt">
      <line nr="1" mi="0" ci="1" mb="0" cb="2"/>
    </sourcefile>
  </package>
</report>`

test("parseChangedLines lists added and changed lines, not removed ones", () => {
  const changed = parseChangedLines(DIFF)
  assert.deepEqual(changed.get("app/src/main/java/com/x/A.kt"), [3, 4, 20, 21, 22, 42])
})

test("parseChangedLines ignores deleted files", () => {
  assert.equal(parseChangedLines(DIFF).has("app/src/main/java/com/x/Old.kt"), false)
  assert.equal(parseChangedLines(DIFF).size, 1)
})

test("parseChangedLines of an empty diff is empty", () => {
  assert.equal(parseChangedLines("").size, 0)
})

test("parseReport maps sourcefiles to full repository paths with branch counters", () => {
  const report = parseReport(REPORT)
  const a = report.get("app/src/main/java/com/x/A.kt")
  assert.deepEqual(a.get(4), { missed: 1, covered: 1 })
  assert.deepEqual(a.get(3), { missed: 0, covered: 0 })
  assert.deepEqual(report.get("app/src/main/java/com/y/B.kt").get(1), { missed: 0, covered: 2 })
})

test("parseReport skips lines without branch counters", () => {
  const report = parseReport(
    `<package name="p"><sourcefile name="F.kt"><line nr="1" mi="0" ci="1"/></sourcefile></package>`,
  )
  assert.equal(report.get("app/src/main/java/p/F.kt").size, 0)
})

test("computeDiffCoverage counts only branches on changed lines", () => {
  const result = computeDiffCoverage(parseChangedLines(DIFF), parseReport(REPORT))
  // Changed lines with branches: 4 (1/2), 20 (2/2), 21 (0/2), 42 (4/4). Line 99 is not changed.
  assert.equal(result.covered, 7)
  assert.equal(result.total, 10)
  assert.equal(result.percent, 70)
  assert.deepEqual(result.misses, [
    {
      path: "app/src/main/java/com/x/A.kt",
      lines: [
        { line: 4, missed: 1, branches: 2 },
        { line: 21, missed: 2, branches: 2 },
      ],
    },
  ])
})

test("computeDiffCoverage ignores files the report does not contain", () => {
  const changed = new Map([["app/src/test/java/com/x/ATest.kt", [1, 2, 3]]])
  const result = computeDiffCoverage(changed, parseReport(REPORT))
  assert.equal(result.total, 0)
  assert.equal(result.percent, 100)
  assert.deepEqual(result.misses, [])
})

test("formatResult passes at the threshold and fails below it", () => {
  const result = { covered: 9, total: 10, percent: 90, misses: [] }
  assert.equal(formatResult(result, 90).ok, true)
  assert.equal(formatResult({ ...result, covered: 8, percent: 80 }, 90).ok, false)
})

test("formatResult lists the lines that miss branches", () => {
  const result = computeDiffCoverage(parseChangedLines(DIFF), parseReport(REPORT))
  const { ok, text } = formatResult(result, 90)
  assert.equal(ok, false)
  assert.match(text, /70\.00% \(7\/10 branches\)/)
  assert.match(text, /A\.kt:21 \(2 of 2 branches missed\)/)
  assert.match(text, /Below the 90% required/)
})

test("formatResult passes when there are no branches", () => {
  const { ok, text } = formatResult({ covered: 0, total: 0, percent: 100, misses: [] }, 90)
  assert.equal(ok, true)
  assert.match(text, /no branches to cover/)
})

test("parseArgs reads the options and applies defaults", () => {
  const options = parseArgs(["--base", "origin/main"])
  assert.equal(options.base, "origin/main")
  assert.equal(options.threshold, 90)
  assert.match(options.report, /jacocoTestReport\.xml$/)
  const custom = parseArgs(["--base", "b", "--report", "r.xml", "--threshold", "75"])
  assert.equal(custom.report, "r.xml")
  assert.equal(custom.threshold, 75)
})

test("parseArgs rejects bad input", () => {
  assert.throws(() => parseArgs([]), /--base/)
  assert.throws(() => parseArgs(["--base"]), /Missing value/)
  assert.throws(() => parseArgs(["--nope", "x"]), /Unknown option/)
  assert.throws(() => parseArgs(["--base", "b", "--threshold", "abc"]), /number/)
})

const A = "app/src/main/java/com/x/A.kt"

test("parseChangedLines handles several files in one diff", () => {
  const diff = `diff --git a/${A} b/${A}
--- a/${A}
+++ b/${A}
@@ -1,0 +2,2 @@
+a
+b
diff --git a/app/src/main/java/com/y/B.kt b/app/src/main/java/com/y/B.kt
--- a/app/src/main/java/com/y/B.kt
+++ b/app/src/main/java/com/y/B.kt
@@ -5 +5 @@
-a
+b
`
  const changed = parseChangedLines(diff)
  assert.deepEqual(changed.get(A), [2, 3])
  assert.deepEqual(changed.get("app/src/main/java/com/y/B.kt"), [5])
})

test("parseChangedLines follows a rename to its new path", () => {
  const diff = `diff --git a/app/src/main/java/com/x/Old.kt b/${A}
similarity index 80%
rename from app/src/main/java/com/x/Old.kt
rename to ${A}
--- a/app/src/main/java/com/x/Old.kt
+++ b/${A}
@@ -7 +7,2 @@
-x
+y
+z
`
  const changed = parseChangedLines(diff)
  assert.deepEqual([...changed.keys()], [A])
  assert.deepEqual(changed.get(A), [7, 8])
})

test("parseChangedLines of a pure rename has no lines", () => {
  const diff = `diff --git a/app/src/main/java/com/x/Old.kt b/${A}
similarity index 100%
rename from app/src/main/java/com/x/Old.kt
rename to ${A}
`
  assert.equal(parseChangedLines(diff).size, 0)
})

// Counters as JaCoCo writes them: one line per branching source line.
function reportWith(counters) {
  const lines = counters
    .map(([nr, mb, cb]) => `<line nr="${nr}" mi="0" ci="1" mb="${mb}" cb="${cb}"/>`)
    .join("")
  return `<package name="com/x"><sourcefile name="A.kt">${lines}</sourcefile></package>`
}

test("exactly 90.00% of the branches passes, just below fails", () => {
  const changed = new Map([[A, [1, 2]]])
  const exact = computeDiffCoverage(changed, parseReport(reportWith([[1, 0, 6], [2, 1, 3]])))
  assert.equal(exact.covered, 9)
  assert.equal(exact.total, 10)
  assert.equal(exact.percent, 90)
  const atThreshold = formatResult(exact, 90)
  assert.equal(atThreshold.ok, true)
  assert.match(atThreshold.text, /90\.00% \(9\/10 branches\)/)

  const below = computeDiffCoverage(changed, parseReport(reportWith([[1, 2, 8], [2, 0, 9]])))
  assert.equal(below.covered, 17)
  assert.equal(below.total, 19)
  assert.equal(formatResult(below, 90).ok, false)

  const just = computeDiffCoverage(changed, parseReport(reportWith([[1, 1001, 8999], [2, 0, 0]])))
  assert.ok(just.percent < 90 && just.percent > 89.98)
  const shown = formatResult(just, 90)
  assert.equal(shown.ok, false)
  assert.match(shown.text, /89\.99% /)
})

test("a changed source missing from the report fails, even with no branches elsewhere", () => {
  const changed = new Map([[A, [1]]])
  const result = computeDiffCoverage(changed, parseReport(""))
  assert.deepEqual(result.unreported, [A])
  const { ok, text } = formatResult(result, 90)
  assert.equal(ok, false)
  assert.match(text, /missing from the coverage report/)
  assert.match(text, /A\.kt/)
})

test("a changed source missing from the report fails even when the others pass", () => {
  const changed = new Map([
    [A, [1]],
    ["app/src/main/java/com/x/Gone.kt", [1]],
  ])
  const result = computeDiffCoverage(changed, parseReport(reportWith([[1, 0, 2]])))
  assert.equal(result.percent, 100)
  assert.equal(formatResult(result, 90).ok, false)
})

test("a source in the report with no branches is fine", () => {
  const xml = `<package name="com/x"><class name="com/x/A" sourcefilename="A.kt"></class></package>`
  const result = computeDiffCoverage(new Map([[A, [1, 2]]]), parseReport(xml))
  assert.deepEqual(result.unreported, [])
  assert.equal(formatResult(result, 90).ok, true)
})

test("run passes without Kotlin changes, whatever the report", () => {
  const out = []
  const code = run(["--base", "main"], {
    diff: () => "",
    exists: () => false,
    log: (m) => out.push(m),
  })
  assert.equal(code, 0)
  assert.match(out[0], /No Kotlin source changes/)
})

test("run fails when the report is missing", () => {
  const errors = []
  const code = run(["--base", "main"], {
    diff: () => DIFF,
    exists: () => false,
    error: (m) => errors.push(m),
  })
  assert.equal(code, 1)
  assert.match(errors[0], /Coverage report not found/)
})

test("run returns 1 under the threshold and 0 at it", () => {
  const io = (xml) => ({ diff: () => DIFF, exists: () => true, read: () => xml, log: () => {} })
  assert.equal(run(["--base", "main"], io(REPORT)), 1)
  assert.equal(run(["--base", "main", "--threshold", "70"], io(REPORT)), 0)
})

test("run passes the base to the diff", () => {
  let seen = null
  run(["--base", "origin/main"], {
    diff: (base) => ((seen = base), ""),
    log: () => {},
  })
  assert.equal(seen, "origin/main")
})

test("readDiff returns the added Kotlin lines against the merge base", () => {
  const dir = mkdtempSync(join(tmpdir(), "diff-coverage-"))
  try {
    const git = (...args) =>
      execFileSync(
        "git",
        ["-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false", ...args],
        { cwd: dir, stdio: "pipe" },
      )
    git("init", "-q", "-b", "main")
    mkdirSync(join(dir, "app/src/main/java/com/x"), { recursive: true })
    writeFileSync(join(dir, A), "one\n")
    writeFileSync(join(dir, "notes.md"), "one\n")
    git("add", ".")
    git("commit", "-q", "-m", "base")
    git("checkout", "-q", "-b", "feature")
    writeFileSync(join(dir, A), "one\ntwo\n")
    writeFileSync(join(dir, "notes.md"), "one\ntwo\n")
    git("commit", "-q", "-am", "change")
    const changed = parseChangedLines(readDiff("main", dir))
    assert.deepEqual([...changed.keys()], [A])
    assert.deepEqual(changed.get(A), [2])
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})
