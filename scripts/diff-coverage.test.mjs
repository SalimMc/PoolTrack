import assert from "node:assert/strict"
import { test } from "node:test"
import {
  computeDiffCoverage,
  formatResult,
  parseArgs,
  parseChangedLines,
  parseReport,
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
  assert.match(text, /70\.0% \(7\/10 branches\)/)
  assert.match(text, /A\.kt:21 \(2 of 2 branches missed\)/)
  assert.match(text, /Below the 90% required/)
})

test("formatResult passes when there are no branches", () => {
  const { ok, text } = formatResult({ covered: 0, total: 0, percent: 100, misses: [] }, 90)
  assert.equal(ok, true)
  assert.match(text, /nothing to check/)
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
