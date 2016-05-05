package scala.pickling
package runtime

import scala.language.existentials
import scala.pickling.pickler.AnyPicklerUnpickler
import scala.pickling.spi.PicklerRegistry._
import scala.reflect.{runtime => reflectRuntime}

import scala.pickling.PicklingErrors.{Wrapper, BasePicklingException}
import internal._

trait CustomRuntime {

  /** Runtime [[Pickler]] and [[Unpickler]] for any [[Traversable]].
    *
    * @param elemClass Elem runtime class
    * @param elemTag Elem tag type
    * @param collTag Collection tag type
    * @tparam C Collection type
    */
  def mkRuntimeTravPickler[C]
    (elemClass: Class[_], elemTag: FastTypeTag[_], collTag: FastTypeTag[_],
     elemPickler0: Pickler[_], elemUnpickler0: Unpickler[_])
    (implicit ev: C => Traversable[_]) = new AbstractPicklerUnpickler[C] {

    val elemPickler   = elemPickler0.asInstanceOf[Pickler[AnyRef]]
    val elemUnpickler = elemUnpickler0.asInstanceOf[Unpickler[AnyRef]]

    val isPrimitive = elemTag.isEffectivelyPrimitive

    def tag: FastTypeTag[C] = collTag.asInstanceOf[FastTypeTag[C]]

    def pickle(coll: C, builder: PBuilder): Unit = {
      builder.beginEntry(coll, tag)
      builder.beginCollection(coll.size)

      builder.pushHints()
      if (isPrimitive) {
        builder.hintElidedType(elemTag)
        builder.pinHints()
      }

      (coll: Traversable[_]).asInstanceOf[Traversable[AnyRef]].foreach {
        (elem: AnyRef) =>
          builder putElement { b =>
            elemPickler.pickle(elem, b)
          }
      }

      builder.popHints()
      builder.endCollection()
      builder.endEntry()
    }

    def unpickle(tag: String, preader: PReader): Any = {
      val reader = preader.beginCollection()

      preader.pushHints()
      if (isPrimitive) {
        reader.hintElidedType(elemTag)
        reader.pinHints()
      }

      val length = reader.readLength()
      val newArray = java.lang.reflect.Array
        .newInstance(elemClass, length).asInstanceOf[Array[AnyRef]]

      var i = 0
      while (i < length) {
        try {
          val r = reader.readElement()
          val elem = elemUnpickler.unpickleEntry(r)
          newArray(i) = elem.asInstanceOf[AnyRef]
          i = i + 1
        } catch {
          case e @ BasePicklingException(msg, cause) =>
            throw Wrapper(e,
              s"""Error in unpickle of `mkRuntimeTravPickler`:
                  |collTag: '${collTag.key}'
                  |elemTag: '${elemTag.key}'
                  |Message:""".stripMargin)
          case e: Exception =>
            throw Wrapper(e,
              s"""Error in unpickle of `mkRuntimeTravPickler`:
                  |collTag: '${collTag.key}'
                  |elemTag: '${elemTag.key}'
                  |<essage:""".stripMargin)
        }
      }

      preader.popHints()
      preader.endCollection()
      newArray
    }
  }

  /** Actual runtime [[Unpickler]] of tuples whose types are known.
    *
    * Don't extend [[AbstractUnpickler]] because it is registered
    * in [[RuntimePicklerRegistry]] with custom names.
    */
  class Tuple2RuntimeKnownTagUnpickler[L, R](lhs: Unpickler[L], rhs: Unpickler[R])
      extends AbstractUnpickler[(L,R)] {

    def unpickleField[T](name: String, reader: PReader, unpickler: Unpickler[T]): T = {
      val reader1 = reader.readField(name)
      // TODO - Always elide tags?
      if(unpickler.tag.isEffectivelyPrimitive) reader1.hintElidedType(unpickler.tag)
      unpickler.unpickleEntry(reader1).asInstanceOf[T]
    }
    override def unpickle(tag: String, reader: PReader): Any = {
      (unpickleField("_1", reader, lhs), unpickleField("_2", reader, rhs))
    }
    override def tag: FastTypeTag[(L, R)] =
      FastTypeTag.apply(s"scala.Tuple2[${lhs.tag.key},${rhs.tag.key}}]")
        .asInstanceOf[FastTypeTag[(L,R)]]

  }

  /** Runtime [[Pickler]] and [[Unpickler]] of tuple with unknown types. */
  class Tuple2RuntimePicklerUnpickler extends AbstractPicklerUnpickler[(Any, Any)] {

    val tag = FastTypeTag[(Any, Any)]("scala.Tuple2[scala.Any, scala.Any]")

    def pickleField(name: String, value: Any, builder: PBuilder): Unit = {
      // TODO This pickler should use the known tag if it is passed.
      val (tag1, pickler1) = if (value == null) {
        (FastTypeTag.Null.asInstanceOf[FastTypeTag[Any]],
         Defaults.nullPickler.asInstanceOf[Pickler[Any]])
      } else {
        val clazz = value.getClass
        val tag = FastTypeTag.makeRaw(clazz)
        val pickler = currentRuntime.picklers
          .genPickler(clazz.getClassLoader, clazz, tag)
          .asInstanceOf[Pickler[Any]]
        (tag, pickler)
      }

      builder.putField(name, b => {
        pickler1.pickle(value, b)
      })
    }

    def pickle(picklee: (Any, Any), builder: PBuilder): Unit = {
      builder.beginEntry(picklee, tag)

      val fld1 = picklee._1
      pickleField("_1", fld1, builder)
      val fld2 = picklee._2
      pickleField("_2", fld2, builder)

      builder.endEntry()
    }

    def unpickleField(name: String, reader: PReader): Any = {
      val reader1 = reader.readField(name)
      val tag1 = reader1.beginEntry()

      val value = {
        if (reader1.atPrimitive) {
          reader1.readPrimitive()
        } else {
          val unpickler1 = currentRuntime.picklers
            .genUnpickler(reflectRuntime.currentMirror, tag1)
          try {
            unpickler1.unpickle(tag1, reader1)
          } catch {
            case e@BasePicklingException(msg, cause) =>
              throw Wrapper(e,
                s"""Error in unpickle of '${this.getClass.getName}':
                    |Field name: '$name'
                    |Field tag: '$tag1'
                    |Message:""".stripMargin)
          }
        }
      }
      reader1.endEntry()
      value
    }

    def unpickle(tag: String, reader: PReader): Any = {
      val fld1 = unpickleField("_1", reader)
      val fld2 = unpickleField("_2", reader)
      (fld1, fld2)
    }
  }

  val tuplePicklerGenerator: PicklerUnpicklerGen[(Any, Any)] = { tpe =>
    // TODO - Actually extract the tpe of the internal things.
    val tag = FastTypeTag.apply(tpe.toString)
    new Tuple2RuntimePicklerUnpickler
  }

  val tupleUnpicklerGenerator: UnpicklerGen[(Any,Any)] = {
    case FastTypeTag(_, List(left, right)) =>

      val lhs = currentRuntime.picklers.lookupUnpickler(left.toString)
        .getOrElse(AnyPicklerUnpickler).asInstanceOf[Unpickler[Any]]
      val rhs = currentRuntime.picklers.lookupUnpickler(right.toString)
        .getOrElse(AnyPicklerUnpickler).asInstanceOf[Unpickler[Any]]

      new Tuple2RuntimeKnownTagUnpickler(lhs, rhs)

    case tpe => new Tuple2RuntimePicklerUnpickler
  }

}

