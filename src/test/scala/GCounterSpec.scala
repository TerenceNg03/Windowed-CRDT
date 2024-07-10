import Instances.{*, given}
import org.scalacheck.Prop.forAll
import org.scalatest.*

import flatspec.*
import matchers.*

class GCounterSpec extends AnyFlatSpec with should.Matchers:

  it should "Associativity" in:
    val prop = forAll: (n1: Int, n2: Int, n3: Int) =>
      val gc1 = GCounter.newGCounterWith(n1)(1)
      val gc2 = GCounter.newGCounterWith(n2)(2)
      val gc3 = GCounter.newGCounterWith(n3)(2)
      (gc1 \/ (gc2 \/ gc3)).value == (gc1 \/ gc2 \/ gc3).value
    prop.check()

  it should "Commutativity" in:
    val prop = forAll: (n1: Int, n2: Int) =>
      val gc1 = GCounter.newGCounterWith(n1)(1)
      val gc2 = GCounter.newGCounterWith(n2)(2)
      (gc1 \/ gc2).value == (gc2 \/ gc1).value
    prop.check()
    val prop2 = forAll: (n1: Int, n2: Int) =>
      val gc1 = GCounter.newGCounterWith(n1)(1)
      val gc2 = GCounter.newGCounterWith(n2)(1)
      (gc1 \/ gc2).value == (gc2 \/ gc1).value
    prop2.check()

  it should "Idempotency" in:
    val prop = forAll: (n1: Int, n2: Int) =>
      val gc1 = GCounter.newGCounterWith(n1)(1)
      (gc1 \/ gc1).value == gc1.value
    prop.check()

  it should "pass general test" in:
    var gc1 = GCounter.newGCounterWith(1)(10)
    gc1 = gc1.increase(1)(5)
    assert(gc1.value == 15): Unit
    val gc2 = GCounter.newGCounterWith(2)(9)
    assert((gc1 \/ gc2).value == 15 + 9): Unit
    val gc3 = gc1 \/ gc2
    gc1 = gc1.increase(1)(3)
    assert((gc3 \/ gc1).value == 15 + 9 + 3)
