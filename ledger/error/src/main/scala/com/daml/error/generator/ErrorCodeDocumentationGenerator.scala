// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.error.generator

import java.lang.reflect.Modifier

import com.daml.error.ErrorCode
import com.daml.error.generator.ErrorCodeDocumentationGenerator.{
  acceptedTypeNames,
  deprecatedTypeName,
  explanationTypeName,
  resolutionTypeName,
}
import com.daml.error.{Explanation, Resolution}

import org.reflections.Reflections

import scala.reflect.runtime.{universe => ru}
import scala.jdk.CollectionConverters._

/** Utility that indexes all error code implementations.
  *
  * @param prefix The classpath prefix that should be scanned for finding subtypes of [[ErrorCode]].
  */
class ErrorCodeDocumentationGenerator(prefixes: Array[String] = Array("com.daml")) {

  def getDocItems: Seq[DocItem] = {
    val errorCodes = getErrorCodeInstances

    errorCodes.view.map(_.id).groupBy(identity).collect {
      case (code, occurrences) if occurrences.size > 1 =>
        sys.error(
          s"Error code $code is used ${occurrences.size} times but we require each error code to be unique! " +
            s"Make these error code unique to make this assertion run through"
        )
    }

    errorCodes.map(convertToDocItem).sortBy(_.code)
  }

  private def getErrorCodeInstances: Seq[ErrorCode] =
    new Reflections(prefixes)
      .getSubTypesOf(classOf[ErrorCode])
      .asScala
      .view
      .collect {
        case clazz if !Modifier.isAbstract(clazz.getModifiers) =>
          clazz.getDeclaredField("MODULE$").get(clazz).asInstanceOf[ErrorCode]
      }
      .toSeq

  private def convertToDocItem(error: ErrorCode): DocItem = {
    val ErrorDocumentationAnnotations(explanation, resolution) =
      getErrorDocumentationAnnotations(error)

    DocItem(
      className = error.getClass.getName,
      category = error.category.getClass.getSimpleName.replace("$", ""),
      hierarchicalGrouping = error.parent.docNames.filter(_ != ""),
      conveyance = error.errorConveyanceDocString.getOrElse(""),
      code = error.id,
      explanation = explanation.getOrElse(Explanation("")),
      resolution = resolution.getOrElse(Resolution("")),
    )
  }

  private case class ErrorDocumentationAnnotations(
      explanation: Option[Explanation],
      resolution: Option[Resolution],
  )

  private def getErrorDocumentationAnnotations(error: ErrorCode): ErrorDocumentationAnnotations = {
    val mirror = ru.runtimeMirror(getClass.getClassLoader)
    val mirroredType = mirror.reflect(error)
    val annotations: Seq[ru.Annotation] = mirroredType.symbol.annotations
    getAnnotations(annotations)
  }

  private case class GetAnnotationsState(
      explanation: Option[Explanation],
      resolution: Option[Resolution],
  )

  private def getAnnotations(
      annotations: Seq[ru.Annotation]
  ): ErrorDocumentationAnnotations = {

    def update(
        state: GetAnnotationsState,
        updatedExplanation: Option[String] = None,
        updatedResolution: Option[String] = None,
    ): GetAnnotationsState = {

      def updateString(
          existing: Option[String],
          updated: Option[String],
          designation: String,
      ): Option[String] =
        updated.fold(ifEmpty = existing) { value: String =>
          if (existing.isDefined)
            sys.error(s"Multiple $designation annotations detected")
          else
            Some(value)
        }

      val existingExplanation = state.explanation
      val updatedExplanationString =
        updateString(existingExplanation.map(_.explanation), updatedExplanation, "explanation")
      val existingResolution = state.resolution
      val updatedResolutionString =
        updateString(existingResolution.map(_.resolution), updatedResolution, "resolution")
      GetAnnotationsState(
        updatedExplanationString.map(Explanation),
        updatedResolutionString.map(Resolution),
      )
    }

    val doc = annotations.foldLeft(GetAnnotationsState(None, None)) { case (state, annotation) =>
      if (isAnnotation(annotation, deprecatedTypeName))
        state
      else if (isAnnotation(annotation, explanationTypeName))
        update(state, updatedExplanation = Some(parseAnnotationValue(annotation.tree)))
      else if (isAnnotation(annotation, resolutionTypeName))
        update(state, updatedResolution = Some(parseAnnotationValue(annotation.tree)))
      else
        sys.error(
          s"Unexpected annotation detected (${annotations.map(annotationTypeName)} but the only supported ones are $acceptedTypeNames)."
        )
    }

    ErrorDocumentationAnnotations(doc.explanation, doc.resolution)
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def parseAnnotationValue(tree: ru.Tree): String = {
    try {
      // get second (index starts at 0) child of tree as it contains the first value of the annotation
      Seq(1).map(tree.children(_).asInstanceOf[ru.Literal].value.value.asInstanceOf[String]) match {
        case s :: Nil => s.stripMargin
        case _ => sys.exit(1)
      }
    } catch {
      case x: RuntimeException =>
        println(
          "Failed to process description (description needs to be a constant-string. i.e. don't apply stripmargin here ...): " + tree.toString
        )
        throw x
    }
  }

  private def isAnnotation(annotation: ru.Annotation, typeName: String): Boolean =
    annotationTypeName(annotation) == typeName

  private def annotationTypeName(annotation: ru.Annotation) =
    annotation.tree.tpe.toString
}

private object ErrorCodeDocumentationGenerator {

  private val deprecatedTypeName = classOf[deprecated].getTypeName.replace("scala.", "")
  private val explanationTypeName = classOf[Explanation].getTypeName.replace("$", ".")
  private val resolutionTypeName = classOf[Resolution].getTypeName.replace("$", ".")

  private val acceptedTypeNames = Set(deprecatedTypeName, explanationTypeName, resolutionTypeName)
}