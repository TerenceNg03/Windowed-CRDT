import org.scalatest._
import flatspec._
import matchers._
import org.scalacheck.Prop.forAll
import Instances.{given, *}
import Types.CRDT

class GMapSpec extends AnyFlatSpec with should.Matchers:

  it should "pass general test" in:
    val gs =
      (x: Set[Int]) => summon[CRDT[GSet[Int], Set[Int], Int]].newCRDT(x)(0)
    val gm1 = summon[CRDT[GMap[String, GSet[Int]], ?, Int]]
      .newCRDT(Map("k1" -> gs(Set(1, 2))))(0)
    val gm2 = summon[CRDT[GMap[String, GSet[Int]], ?, Int]]
      .newCRDT(Map("k1" -> gs(Set(2, 3))))(0)
    assert((gm1 \/ gm2).read() == Map("k1" -> gs(Set(1, 2, 3))))
    val gm3 = summon[CRDT[GMap[String, GSet[Int]], ?, Int]]
      .newCRDT(Map("k2" -> gs(Set(4, 3))))(0)
    assert(
      (gm1 \/ gm3).read() == Map("k1" -> gs(Set(1, 2)), "k2" -> gs(Set(3, 4)))
    )
