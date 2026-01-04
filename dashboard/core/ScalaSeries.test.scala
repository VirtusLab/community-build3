package dashboard.core

import munit.FunSuite

class ScalaSeriesTest extends FunSuite:

  test("categorize LTS 3.3 versions"):
    assertEquals(ScalaSeries.fromScalaVersion("3.3.0"), ScalaSeries.Lts33)
    assertEquals(ScalaSeries.fromScalaVersion("3.3.1"), ScalaSeries.Lts33)
    assertEquals(ScalaSeries.fromScalaVersion("3.3.4"), ScalaSeries.Lts33)
    assertEquals(ScalaSeries.fromScalaVersion("3.3.8-RC1-bin-20251229-7461c65"), ScalaSeries.Lts33)

  test("categorize LTS 3.9 versions"):
    assertEquals(ScalaSeries.fromScalaVersion("3.9.0"), ScalaSeries.Lts39)
    assertEquals(ScalaSeries.fromScalaVersion("3.9.1-RC1"), ScalaSeries.Lts39)

  test("categorize Next versions (non-LTS)"):
    assertEquals(ScalaSeries.fromScalaVersion("3.4.0"), ScalaSeries.Next)
    assertEquals(ScalaSeries.fromScalaVersion("3.5.2"), ScalaSeries.Next)
    assertEquals(ScalaSeries.fromScalaVersion("3.6.0-RC1"), ScalaSeries.Next)
    assertEquals(ScalaSeries.fromScalaVersion("3.7.1"), ScalaSeries.Next)
    assertEquals(ScalaSeries.fromScalaVersion("3.8.0"), ScalaSeries.Next)
    assertEquals(ScalaSeries.fromScalaVersion("3.8.0-RC5"), ScalaSeries.Next)
    assertEquals(ScalaSeries.fromScalaVersion("3.8.1-RC1-bin-20260102-32e416e-NIGHTLY"), ScalaSeries.Next)

class VersionTypeTest extends FunSuite:

  test("identify stable versions"):
    assertEquals(VersionType.fromScalaVersion("3.8.0"), VersionType.Stable)
    assertEquals(VersionType.fromScalaVersion("3.3.4"), VersionType.Stable)
    assertEquals(VersionType.fromScalaVersion("3.9.0"), VersionType.Stable)

  test("identify RC versions"):
    assertEquals(VersionType.fromScalaVersion("3.8.0-RC5"), VersionType.RC)
    assertEquals(VersionType.fromScalaVersion("3.8.0-RC1"), VersionType.RC)
    assertEquals(VersionType.fromScalaVersion("3.6.0-RC2"), VersionType.RC)

  test("identify NIGHTLY versions"):
    assertEquals(VersionType.fromScalaVersion("3.8.1-RC1-bin-20251228-e73ff2c-NIGHTLY"), VersionType.Nightly)
    assertEquals(VersionType.fromScalaVersion("3.8.1-RC1-bin-20260102-32e416e-NIGHTLY"), VersionType.Nightly)

  test("identify snapshot versions (bin without NIGHTLY)"):
    assertEquals(VersionType.fromScalaVersion("3.8.0-RC4-bin-20251230-fab225a"), VersionType.Snapshot)
    assertEquals(VersionType.fromScalaVersion("3.7.2-bin-20240115-abc1234"), VersionType.Snapshot)

  test("shouldShow filters out snapshots when excludeSnapshots is true"):
    // Keep these
    assert(VersionType.shouldShow("3.8.0", excludeSnapshots = true, excludeNightlies = false))
    assert(VersionType.shouldShow("3.8.0-RC5", excludeSnapshots = true, excludeNightlies = false))
    assert(
      VersionType.shouldShow(
        "3.8.1-RC1-bin-20251228-e73ff2c-NIGHTLY",
        excludeSnapshots = true,
        excludeNightlies = false
      )
    )

    // Filter out snapshots
    assert(!VersionType.shouldShow("3.8.0-RC4-bin-20251230-fab225a", excludeSnapshots = true, excludeNightlies = false))

  test("shouldShow filters out nightlies when excludeNightlies is true"):
    // Keep these
    assert(VersionType.shouldShow("3.8.0", excludeSnapshots = false, excludeNightlies = true))
    assert(VersionType.shouldShow("3.8.0-RC5", excludeSnapshots = false, excludeNightlies = true))
    assert(VersionType.shouldShow("3.8.0-RC4-bin-20251230-fab225a", excludeSnapshots = false, excludeNightlies = true))

    // Filter out nightlies
    assert(
      !VersionType.shouldShow(
        "3.8.1-RC1-bin-20251228-e73ff2c-NIGHTLY",
        excludeSnapshots = false,
        excludeNightlies = true
      )
    )

  test("shouldShow filters both when both are true"):
    assert(VersionType.shouldShow("3.8.0", excludeSnapshots = true, excludeNightlies = true))
    assert(VersionType.shouldShow("3.8.0-RC5", excludeSnapshots = true, excludeNightlies = true))
    assert(!VersionType.shouldShow("3.8.0-RC4-bin-20251230-fab225a", excludeSnapshots = true, excludeNightlies = true))
    assert(
      !VersionType.shouldShow(
        "3.8.1-RC1-bin-20251228-e73ff2c-NIGHTLY",
        excludeSnapshots = true,
        excludeNightlies = true
      )
    )

  test("shouldShow keeps everything when both are false"):
    assert(VersionType.shouldShow("3.8.0", excludeSnapshots = false, excludeNightlies = false))
    assert(VersionType.shouldShow("3.8.0-RC5", excludeSnapshots = false, excludeNightlies = false))
    assert(
      VersionType.shouldShow(
        "3.8.1-RC1-bin-20251228-e73ff2c-NIGHTLY",
        excludeSnapshots = false,
        excludeNightlies = false
      )
    )
    assert(VersionType.shouldShow("3.8.0-RC4-bin-20251230-fab225a", excludeSnapshots = false, excludeNightlies = false))
