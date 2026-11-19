package com.relab.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for the reported "works once, closes on second open" failure.
 * Each scenario is fully closed before the next cold Activity launch.
 */
@RunWith(AndroidJUnit4::class)
class StartupRelaunchTest {

    @Test
    fun mainActivitySurvivesTwoSequentialLaunches() {
        repeat(2) {
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                scenario.onActivity { activity ->
                    assertEquals(activity.applicationContext.packageName, activity.packageName)
                    assertEquals(false, activity.isFinishing)
                }
            } finally {
                scenario.close()
            }
        }
    }

    @Test
    fun splashActivityCanAlsoBeOpenedTwice() {
        repeat(2) {
            val scenario = ActivityScenario.launch(SplashActivity::class.java)
            try {
                scenario.onActivity { activity ->
                    assertEquals(activity.applicationContext.packageName, activity.packageName)
                    assertEquals(false, activity.isFinishing)
                }
            } finally {
                scenario.close()
            }
        }
    }
}
