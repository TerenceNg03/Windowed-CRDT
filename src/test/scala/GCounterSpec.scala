import Instances.{*, given}
import org.scalacheck.Prop.forAll
import org.scalatest.*

import flatspec.*
import matchers.*

class GCounterSpec extends AnyFlatSpec with should.Matchers:

  it should "Associativity" in:
    val prop = forAll: (n1: Int, n2: Int, n3: Int) =>
      val gc1 = GCounter.newGCounter(n1)(1)
      val gc2 = GCounter.newGCounter(n2)(2)
      val gc3 = GCounter.newGCounter(n3)(2)
      (gc1 \/ (gc2 \/ gc3)).read() == (gc1 \/ gc2 \/ gc3).read()
    prop.check()

  it should "Commutativity" in:
    val prop = forAll: (n1: Int, n2: Int) =>
      val gc1 = GCounter.newGCounter(n1)(1)
      val gc2 = GCounter.newGCounter(n2)(2)
      (gc1 \/ gc2).read() == (gc2 \/ gc1).read()
    prop.check()
    val prop2 = forAll: (n1: Int, n2: Int) =>
      val gc1 = GCounter.newGCounter(n1)(1)
      val gc2 = GCounter.newGCounter(n2)(1)
      (gc1 \/ gc2).read() == (gc2 \/ gc1).read()
    prop2.check()

  it should "Idempotency" in:
    val prop = forAll: (n1: Int, n2: Int) =>
      val gc1 = GCounter.newGCounter(n1)(1)
      (gc1 \/ gc1).read() == gc1.read()
    prop.check()

  it should "pass general test" in:
    var gc1 = GCounter.newGCounter(10)(1)
    gc1 = gc1.increase(5)
    assert(gc1.read() == 15)
    var gc2 = GCounter.newGCounter(9)(2)
    assert((gc1 \/ gc2).read() == 15 + 9)
    var gc3 = gc1 \/ gc2
    gc1 = gc1.increase(3)
    assert((gc3 \/ gc1).read() == 15 + 9 + 3)
