package net.zomis.games

import net.zomis.Best

private fun flipX(start: Position): Position {
    return Position(start.sizeX - 1 - start.x, start.y, start.sizeX, start.sizeY)
}
private fun flipY(start: Position): Position {
    return Position(start.x, start.sizeY - 1 - start.y, start.sizeX, start.sizeY)
}
private fun rotate(start: Position): Position {
    return Position(start.sizeY - 1 - start.y, start.x, start.sizeY, start.sizeX)
}

data class Position(val x: Int, val y: Int, val sizeX: Int, val sizeY: Int) {
    fun next(): Position? {
        if (x == sizeX - 1) {
            return if (y == sizeY - 1) null else Position(0, y + 1, sizeX, sizeY)
        }
        return Position(this.x + 1, this.y, this.sizeX, this.sizeY)
    }
}

enum class TransformationType(val transforming: (Position) -> Position, val reverse: (Position) -> Position) {
    FLIP_X(::flipX, ::flipX),
    FLIP_Y(::flipY, ::flipY),
    ROTATE(::rotate, { start -> rotate(rotate(rotate(start))) }),
    ;
}

enum class Transformation(private val transformations: List<TransformationType>) {
    NO_CHANGE(listOf()),
    FLIP_X(listOf(TransformationType.FLIP_X)),
    FLIP_Y(listOf(TransformationType.FLIP_Y)),
    ROTATE_90(listOf(TransformationType.ROTATE)),
    ROTATE_180(listOf(TransformationType.ROTATE, TransformationType.ROTATE)),
    ROTATE_270(listOf(TransformationType.ROTATE, TransformationType.ROTATE, TransformationType.ROTATE)),
    ROTATE_90_FLIP_X(listOf(TransformationType.ROTATE, TransformationType.FLIP_X)),
    ROTATE_90_FLIP_Y(listOf(TransformationType.ROTATE, TransformationType.FLIP_Y)),
    ;

    fun transform(position: Position): Position {
        return transformations.fold(position) { pos, trans -> trans.transforming(pos) }
    }

    fun reverseTransform(position: Position): Position {
        return transformations.reversed().fold(position) { pos, trans -> trans.reverse(pos) }
    }

    private val referencePoints = arrayOf(
        Position(3, 2, 5, 5),
        Position(4, 0, 5, 5)
    )
    fun apply(transformation: Transformation): Transformation {
        val simplestTransformation = Transformation.values().filter {result ->
            referencePoints.all {p -> transformation.transform(this.transform(p)) == result.transform(p) }
        }
        return simplestTransformation.single()
    }

}

class Map2D<T>(val sizeX: Int, val sizeY: Int, val getter: (x: Int, y: Int) -> T, val setter: (x: Int, y: Int, value: T) -> Unit) {

    fun standardizedTransformation(valueFunction: (T) -> Int): Transformation {
        // keep a Set<Transformation>, start with all of them
        // loop through Transformations and find ones with the extremes
        // the goal is that of all the possible transformations, the result should be the one with the lowest/highest value

        // start in the possible fields for the target map upper-left corner
        // then continue, line by line, beginning with increasing X and then increase Y
        val possibleTransformations = Transformation.values().toMutableSet()
        if (sizeX != sizeY) {
            // Rotating 90 or 270 degrees only works if both width or height is the same
            possibleTransformations.remove(Transformation.ROTATE_90)
            possibleTransformations.remove(Transformation.ROTATE_270)
        }

        var position: Position? = Position(0, 0, sizeX, sizeY)
        while (possibleTransformations.size > 1 && position != null) {
            val best = Best<Transformation> { transformation ->
                val originalPos = transformation.reverseTransform(position!!)
                val originalT = getter(originalPos.x, originalPos.y)
                valueFunction(originalT).toDouble()
            }
            possibleTransformations.forEach {
                best.next(it)
            }
            possibleTransformations.retainAll(best.getBest())

            position = position.next()
        }

        val transformation = possibleTransformations.first() // map can be symmetric so don't use .single
        return transformation
    }

    fun transform(transformation: Transformation) {
        val rotated = Map2DX(sizeX, sizeY) { x, y ->
            val pos = Position(x, y, sizeX, sizeY)
            val oldPos = transformation.reverseTransform(pos)
            getter(oldPos.x, oldPos.y)
        }

        (0 until sizeY).forEach {y ->
            (0 until sizeX).forEach {x ->
                setter(x, y, rotated.grid[y][x])
            }
        }
    }

    fun standardize(valueFunction: (T) -> Int) {
        this.transform(this.standardizedTransformation(valueFunction))
    }

}

class Map2DX<T>(val sizeX: Int, val sizeY: Int, val factory: (x: Int, y: Int) -> T) {

    val grid: MutableList<MutableList<T>> = (0 until sizeY).map { y ->
        (0 until sizeX).map { x ->
            factory(x, y)
        }.toMutableList()
    }.toMutableList()

    fun set(x: Int, y: Int, value: T) {
        grid[y][x] = value
    }

    fun asMap2D(): Map2D<T> {
        return Map2D(sizeX, sizeY, { x, y -> grid[y][x] }) {x, y, v ->
            grid[y][x] = v
        }
    }

    fun standardize(value: (T) -> Int): Map2DX<T> {
        asMap2D().standardize(value)
        return this
    }

    override fun toString(): String {
        return "Map2D(grid=$grid)"
    }

}
