import org.scalatest._
import flatspec._
import matchers._
import org.scalacheck.Prop.forAll
import Instances.{given, *}
import Types.CRDT

class GCounterSpec extends AnyFlatSpec with should.Matchers:

  it should "Associativity" in:
    val prop = forAll: (n1: Int, n2: Int, n3: Int) =>
      val gc1 = summon[CRDT[GCounter[Int, Int], Int, Int]].newCRDT(n1)(1)
      val gc2 = summon[CRDT[GCounter[Int, Int], Int, Int]].newCRDT(n2)(2)
      val gc3 = summon[CRDT[GCounter[Int, Int], Int, Int]].newCRDT(n3)(2)
      (gc1 \/ (gc2 \/ gc3)).read() == (gc1 \/ gc2 \/ gc3).read()
    prop.check()

  it should "Commutativity" in:
    val prop = forAll: (n1: Int, n2: Int) =>
      val gc1 = summon[CRDT[GCounter[Int, Int], Int, Int]].newCRDT(n1)(1)
      val gc2 = summon[CRDT[GCounter[Int, Int], Int, Int]].newCRDT(n2)(2)
      (gc1 \/ gc2).read() == (gc2 \/ gc1).read()
    prop.check()
    val prop2 = forAll: (n1: Int, n2: Int) =>
      val gc1 = summon[CRDT[GCounter[Int, Int], Int, Int]].newCRDT(n1)(1)
      val gc2 = summon[CRDT[GCounter[Int, Int], Int, Int]].newCRDT(n2)(1)
      (gc1 \/ gc2).read() == (gc2 \/ gc1).read()
    prop2.check()

  it should "Idempotency" in:
    val prop = forAll: (n1: Int, n2: Int) =>
      val gc1 = summon[CRDT[GCounter[Int, Int], Int, Int]].newCRDT(n1)(1)
      (gc1 \/ gc1).read() == gc1.read()
    prop.check()

  it should "pass general test" in:
    var gc1 = summon[CRDT[GCounter[Int, Int], Int, Int]].newCRDT(10)(1)
    gc1 = gc1.increase(5)
    assert(gc1.read() == 15)
    var gc2 = summon[CRDT[GCounter[Int, Int], Int, Int]].newCRDT(9)(2)
    assert((gc1 \/ gc2).read() == 15+9)
    var gc3 = gc1 \/ gc2
    gc1 = gc1.increase(3)
    assert((gc3 \/ gc1).read() == 15+9+3)