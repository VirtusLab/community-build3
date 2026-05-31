package dashboard.core

import munit.FunSuite

class ProjectNameTest extends FunSuite:

  test("parse valid org/repo format"):
    assert(ProjectName("typelevel/cats").isRight)
    assert(ProjectName("scala/scala3").isRight)

  test("convert legacy org_repo format to org/repo"):
    val result = ProjectName("typelevel_cats")
    assert(result.isRight)
    assertEquals(result.map(_.searchName), Right("typelevel/cats"))

  test("reject invalid formats"):
    assert(ProjectName("invalid").isLeft)
    assert(ProjectName("").isLeft)
    assert(ProjectName("/repo").isLeft)
    assert(ProjectName("org/").isLeft)

  test("extract org and repo"):
    val name = ProjectName.unsafeApply("typelevel/cats")
    assertEquals(name.org, "typelevel")
    assertEquals(name.repo, "cats")

  test("provide search names"):
    val name = ProjectName.unsafeApply("typelevel/cats")
    assertEquals(name.searchName, "typelevel/cats")
    assertEquals(name.legacySearchName, "typelevel_cats")

class SemVersionTest extends FunSuite:

  test("parse valid versions"):
    assert(SemVersion("3.3.0").isDefined)
    assert(SemVersion("3.3.0-RC1").isDefined)
    assert(SemVersion("3.3.0-RC1-bin-20240101-hash-NIGHTLY").isDefined)

  test("reject invalid versions"):
    assert(SemVersion("invalid").isEmpty)
    assert(SemVersion("3").isEmpty)
    assert(SemVersion("3.3").isEmpty)

  test("compare versions correctly"):
    val v330 = SemVersion.unsafeApply("3.3.0")
    val v331 = SemVersion.unsafeApply("3.3.1")
    val v340 = SemVersion.unsafeApply("3.4.0")

    assert(v331.isNewerThan(v330))
    assert(v340.isNewerThan(v331))
    assert(!v330.isNewerThan(v331))

  test("compare minor versions numerically"):
    val v310 = SemVersion.unsafeApply("3.10.0-RC1-bin-20260529-80ddc38")
    val v320 = SemVersion.unsafeApply("3.2.0")
    val v331 = SemVersion.unsafeApply("3.3.1")

    given Ordering[SemVersion] = SemVersion.given_Ordering_SemVersion
    assert(Ordering[SemVersion].compare(v310, v320) > 0)
    assert(Ordering[SemVersion].compare(v310, v331) > 0)
    assert(v310.isNewerThan(v320))

  test("compare nightly builds by rc then date"):
    val newer = SemVersion.unsafeApply("3.9.0-RC1-bin-20260522-7db439f-NIGHTLY")
    val older = SemVersion.unsafeApply("3.9.0-RC1-bin-20260513-5ab0f25")
    val bareRc = SemVersion.unsafeApply("3.9.0-RC1")

    given Ordering[SemVersion] = SemVersion.given_Ordering_SemVersion
    assert(Ordering[SemVersion].compare(newer, older) > 0)
    assert(Ordering[SemVersion].compare(newer, bareRc) > 0)
    assert(Ordering[SemVersion].compare(older, bareRc) > 0)

  test("compare rc number before bin date"):
    val rc3 = SemVersion.unsafeApply("3.8.4-RC3")
    val rc2WithBin = SemVersion.unsafeApply("3.8.4-RC2-bin-20260520-cd2952e")

    given Ordering[SemVersion] = SemVersion.given_Ordering_SemVersion
    assert(Ordering[SemVersion].compare(rc3, rc2WithBin) > 0)

  test("compare same rc by bin date"):
    val newer = SemVersion.unsafeApply("3.8.4-RC2-bin-20260520-cd2952e")
    val older = SemVersion.unsafeApply("3.8.4-RC2-bin-20260510-9ada879")
    val bareRc = SemVersion.unsafeApply("3.8.4-RC2")

    given Ordering[SemVersion] = SemVersion.given_Ordering_SemVersion
    assert(Ordering[SemVersion].compare(newer, older) > 0)
    assert(Ordering[SemVersion].compare(newer, bareRc) > 0)

  test("stable version is newer than RC"):
    val stable = SemVersion.unsafeApply("3.3.0")
    val rc = SemVersion.unsafeApply("3.3.0-RC1")

    given Ordering[SemVersion] = SemVersion.given_Ordering_SemVersion
    assert(Ordering[SemVersion].compare(stable, rc) > 0)

  test("isNewerOrEqualTo includes equal versions"):
    val v330 = SemVersion.unsafeApply("3.3.0")
    assert(v330.isNewerOrEqualTo(v330))
