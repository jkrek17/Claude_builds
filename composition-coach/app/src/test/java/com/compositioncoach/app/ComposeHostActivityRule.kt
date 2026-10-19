package com.compositioncoach.app

import android.content.ComponentName
import androidx.activity.ComponentActivity
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * Registers the empty [ComponentActivity] that `createComposeRule()` launches. The debug build gets it from
 * `ui-test-manifest` (a `debugImplementation`, so it never ships); the release unit tests that `:app:check` also
 * runs have no such manifest entry, so Robolectric could not resolve the activity. Put it outside the compose
 * rule: `RuleChain.outerRule(ComposeHostActivityRule()).around(rule)`.
 */
class ComposeHostActivityRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val app = RuntimeEnvironment.getApplication()
            Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
            base.evaluate()
        }
    }
}
