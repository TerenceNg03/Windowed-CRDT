import Instances.{NumericCRDT, given}
import Types.CRDT
import Types.Lattice
import scala.math.Ordering.Implicits.infixOrderingOps

class NumericCRDTTest extends munit.FunSuite {
  test("Increase") {
    val a =
      summon[CRDT[NumericCRDT[Int], Int]].pure(1).modify(9)
    val b = summon[CRDT[NumericCRDT[Int], Int]].pure(10)
    assert(a equiv b, clue = s"${a.run()} vs ${b.run()}")
  }

  test("Decrease") {
    val a =
      summon[CRDT[NumericCRDT[Int], Int]].pure(15).modify(-5)
    val b = summon[CRDT[NumericCRDT[Int], Int]].pure(10)
    print(summon[Ordering[NumericCRDT[Int]]].compare(a,b))
    assert(a equiv b, clue = s"${a.run()} vs ${b.run()}")
  }

  test("join") {
    var a =
      summon[CRDT[NumericCRDT[Int], Int]].pure(15)
    val x = a.fork()
    a = a.modify(-10)
    val b = summon[CRDT[NumericCRDT[Int], Int]].pure(5)
    assert((a \/ x) equiv b, clue = s"${(a \/ x).run()} vs ${b.run()}")
  }

  test("meet") {
    var a =
      summon[CRDT[NumericCRDT[Int], Int]].pure(15)
    val x = a.fork()
    a = a.modify(-10)
    val b = summon[CRDT[NumericCRDT[Int], Int]].pure(15)
    assert((a /\ x) equiv b, clue = s"${(a /\ x).run()} vs ${b.run()}")
  }
}
