import Instances.{*, given}
import org.scalatest.*

import flatspec.*
import matchers.*

class GMapSpec extends AnyFlatSpec with should.Matchers:

  it should "pass general test" in:
    val gs =
      (x: Set[Int]) => GSet.newGSet(x)
    val gm1 = GMap.newGMap(Map("k1" -> gs(Set(1, 2))))
    val gm2 = GMap.newGMap(Map("k1" -> gs(Set(2, 3))))
    assert((gm1 \/ gm2) == Map("k1" -> gs(Set(1, 2, 3)))): Unit
    val gm3 = GMap.newGMap(Map("k2" -> gs(Set(4, 3))))
    assert(
      (gm1 \/ gm3) == Map("k1" -> gs(Set(1, 2)), "k2" -> gs(Set(3, 4)))
    )
