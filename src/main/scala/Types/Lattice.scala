package Types

trait Lattice[A]:
    extension (a:A) infix def \/(b: A): A
    extension (a:A) infix def /\(b: A): A


trait BoundedJoinSemiLattice[A] extends Lattice[A]:
    val bottom:A

trait BoundedMeetSemiLattice[A] extends Lattice[A]:
    val top:A