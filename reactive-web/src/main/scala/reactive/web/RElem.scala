package reactive
package web

import scala.xml._

import net.liftweb.util.Helpers._

/**
 * Wraps an `Elem=>Elem` as a `NodeSeq=>NodeSeq` for compatibility
 * with Lift binding / css selectors.
 * You can also use it as an `Elem=>Elem` by explicitly calling
 * the `apply(Elem): Elem` overload.
 */
class ElemFuncWrapper(renderer: Elem => Elem) extends (NodeSeq => NodeSeq) {
  def apply(elem: Elem): Elem = renderer(elem)
  /**
   * Like `apply(Elem)`. Needed to implement `(NodeSeq=>NodeSeq)`,
   * as required by Lift's binding/css selectors.
   * Forces `ns` to an `Elem` by calling `nodeSeqToElem` (in the reactive.web package object)
   */
  def apply(ns: NodeSeq): NodeSeq = apply(nodeSeqToElem(ns))
}

/**
 * This singleton provides some useful things, including factories for creating RElems from standard Scala types.
 */
object RElem {
  /**
   * Given an `Elem`, if it doesn't have an id attribute, add one
   * by calling [[reactive.web.Page#nextId]], or if no `Page` is
   * available generate a random id, and return the
   * new `Elem`.
   */
  def withId(elem: Elem)(implicit page: Page = null): Elem = elem.attributes get "id" match {
    case Some(_) => elem
    case None    => elem % new UnprefixedAttribute("id", Option(page).map(_.nextId) getOrElse "reactiveWebId_" + randomString(7), Null)
  }

  /**
   * Wrap a `NodeSeq` function so that it can access the element's id
   * @param f a curried function that takes the element's id and then
   * the element.
   * @return a function that takes a `NodeSeq` and calls `f` with the
   * element's id and the element. If the `NodeSeq` is not an `Elem`
   * it is converted to one via [[reactive.web.nodeSeqToElem]]. If
   * it has no `id` attribute one is added, via [[withId]].
   * @example {{{
   *   ".sel" #> RElem.withElemId { id =>
   *     onServer[Click]{ _ => println(s"Clicked elem \$id") }
   *   }
   * }}}
   */
  def withElemId[R](f: String => Elem => R)(implicit page: Page): NodeSeq => R = { ns =>
    val e = nodeSeqToElem(ns)
    val el = RElem.withId(e)
    val id = el.attributes("id").text
    f(id)(el)
  }

  /**
   * An RElem based on a scala.xml.Elem.
   * @param baseElem the Elem to use. If it already has an id, it is the programmer's responsibility to ensure it is unique
   * @param children any addition RElems to append
   */
  class ElemWrapper(val baseElem: Elem, children: RElem*) extends RElem {
    def properties = Nil
    def events = Nil
    override def renderer(implicit p: Page) = e => {
      val sup = super.renderer(p)(e)
      sup.copy(child = {
        sup.child ++ children.map(_.render(p))
      })
    }

    override def toString = (baseElem :: children.toList).mkString("ElemWrapper(", ",", ")")
  }

  private[reactive] val elems = new scala.collection.mutable.WeakHashMap[String, RElem] //TODO

  /**
   * Creates an RElem from the given scala.xml.Elem. One may provide 0 or more RElems to append.
   * @param parent the Elem to use. If it has an id you must ensure it is unique
   * @param children any children to append
   */
  def apply(parent: Elem, children: RElem*): RElem = new ElemWrapper(parent, children: _*)
  /**
   * Creates an RElem from a text String, wrapping it in a span
   */
  def apply(text: String): RElem = new ElemWrapper(<span>{ text }</span>)

  implicit class rElemToNsFunc(rElem: RElem)(implicit page: Page) extends (NodeSeq => NodeSeq) {
    def apply(ns: NodeSeq) = rElem.toNSFunc(page)(ns)
  }
}

/**
 * The base trait of all reactive elements.
 * Has the ability to be rendered. Rendering will generate an Elem that
 * contains attributes representing the current state of properties,
 * and attributes with event handlers. Note that you must add listeners
 * to events before it is rendered; otherwise the attribute may not be
 * generated. DOM events will be sent to the server and appear as an
 * EventStream.
 * In addition properties can be kept in sync with the browser in
 * response to events, and mutating them causes the DOM to be updated
 * in the browser.
 */
trait RElem extends PageIds {
  /**
   * Which Pages this RElem has been rendered to.
   * It will be kept in sync on all of them.
   *
   * Pages are used (a) as an Observing to manage listener
   * references; (b) to link server-context updates
   * with the right comet actor; and (c) to allow
   * the same element state to be maintained on
   * multiple pages.
   */
  protected def pages = pageIds.keys.toSeq

  /**
   * The events that contribute to rendering
   */
  def events: Seq[DomEventSource[_ <: DomEvent]]

  /**
   * The properties that contribute to rendering
   */
  def properties: Seq[PropertyVar[_]]

  /**
   * The Elem used as the basis to render this RElem.
   * The final rendering is contributed to by events
   * and properties as well.
   */
  def baseElem: Elem

  /**
   * Returns an Elem that can be used to initially place this
   * RElem in the page, with attributes defined to set the properties
   * and add the event handlers.
   * Requires a Page in the implicits scope, which is registered
   * with this RElem before generating the Elem.
   * @return an Elem consisting of baseElem plus attributes contributed by events and properties, not to mention the id.
   */
  def render(implicit page: Page): Elem =
    toNSFunc(page)(baseElem.copy(child = Nil))

  /**
   * Returns a (subclass of) NodeSeq=>NodeSeq that takes
   * an Elem and applies this RElem's state to it, and returns
   * a new Elem that has the label specified in the RElem's baseElem,
   * baseElem's children and attributes appended to the Elem's
   * children and attributes, as well as attributes for the
   * properties and events.
   */
  def toNSFunc(implicit page: Page) = new ElemFuncWrapper(renderer(page))

  protected def renderer(implicit page: Page): Elem => Elem = { elem0 =>
    val elem = addPage(baseElem.copy(
      child = elem0.child ++ baseElem.child,
      attributes = elem0.attributes append baseElem.attributes
    ))
    val withProps = properties.foldLeft(elem) {
      case (e, prop) => prop.render(e)(page)
    }
    events.foldLeft[Elem](withProps) {
      case (e, evt: DomEventSource[_]) => e % evt.asAttribute
      case (e, _)                      => e
    }
  }
}
