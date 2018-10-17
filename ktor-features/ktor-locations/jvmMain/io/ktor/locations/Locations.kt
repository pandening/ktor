package io.ktor.locations

import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.routing.*
import io.ktor.util.*
import java.lang.reflect.*
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.*

/**
 * **EXPERIMENTAL** Ktor feature that allows to handle and construct routes in a typed way.
 *
 * You have to create data classes/objects representing parameterized routes and annotate them with [Location].
 * Then you can register sub-routes and handlers for those locations and create links to them
 * using [Locations.href].
 */
open class Locations(private val application: Application, private val routeService: LocationRouteService) {
    private val conversionService: ConversionService get() = application.conversionService
    private val rootUri = ResolvedUriInfo("", emptyList())
    private val info = hashMapOf<KClass<*>, LocationInfo>()

    private class LocationInfoProperty(val name: String, val getter: KProperty1.Getter<*, *>, val isOptional: Boolean)

    private data class ResolvedUriInfo(val path: String, val query: List<Pair<String, String>>)
    private data class LocationInfo(
        val klass: KClass<*>,
        val parent: LocationInfo?,
        val parentParameter: LocationInfoProperty?,
        val path: String,
        val pathParameters: List<LocationInfoProperty>,
        val queryParameters: List<LocationInfoProperty>
    )

    private fun LocationInfo.create(allParameters: Parameters): Any {
        val objectInstance = klass.objectInstance
        if (objectInstance != null) return objectInstance

        val constructor: KFunction<Any> = klass.primaryConstructor ?: klass.constructors.single()
        val parameters = constructor.parameters
        val arguments = parameters.map { parameter ->
            val parameterType = parameter.type
            val parameterName = parameter.name ?: getParameterNameFromAnnotation(parameter)
            val value: Any? = if (parent != null && parameterType == parent.klass.starProjectedType) {
                parent.create(allParameters)
            } else {
                createFromParameters(allParameters, parameterName, parameterType.javaType, parameter.isOptional)
            }
            parameter to value
        }.filterNot { it.first.isOptional && it.second == null }.toMap()

        return constructor.callBy(arguments)
    }

    private fun createFromParameters(parameters: Parameters, name: String, type: Type, optional: Boolean): Any? {
        val values = parameters.getAll(name)
        return when (values) {
            null -> when {
                !optional -> throw DataConversionException("Parameter '$name' was not found in the map")
                else -> null
            }
            else -> conversionService.fromValues(values, type)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun getParameterNameFromAnnotation(parameter: KParameter): String = TODO()

    private fun ResolvedUriInfo.combine(
        relativePath: String,
        queryValues: List<Pair<String, String>>
    ): ResolvedUriInfo {
        val pathElements = (path.split("/") + relativePath.split("/")).filterNot { it.isEmpty() }
        val combinedPath = pathElements.joinToString("/", "/")
        return ResolvedUriInfo(combinedPath, query + queryValues)
    }

    private fun getOrCreateInfo(locationClass: KClass<*>): LocationInfo {
        return info.getOrPut(locationClass) {
            val outerClass = locationClass.java.declaringClass?.kotlin
            val parentInfo = outerClass?.let {
                if (routeService.findRoute(outerClass) != null)
                    getOrCreateInfo(outerClass)
                else
                    null
            }

            val path = routeService.findRoute(locationClass) ?: ""
            if (locationClass.objectInstance != null)
                return@getOrPut LocationInfo(locationClass, parentInfo, null, path, emptyList(), emptyList())

            val constructor: KFunction<Any> =
                locationClass.primaryConstructor
                    ?: locationClass.constructors.singleOrNull()
                    ?: throw IllegalArgumentException("Class $locationClass cannot be instantiated because the constructor is missing")

            val declaredProperties = constructor.parameters.map { parameter ->
                val property =
                    locationClass.declaredMemberProperties.singleOrNull { property -> property.name == parameter.name }
                        ?: throw LocationRoutingException(
                            "Parameter ${parameter.name} of constructor " +
                                "for class ${locationClass.qualifiedName} should have corresponding property"
                        )

                LocationInfoProperty(
                    parameter.name ?: "<unnamed>",
                    (property as KProperty1<out Any?, *>).getter,
                    parameter.isOptional
                )
            }

            val parentParameter = declaredProperties.firstOrNull {
                it.getter.returnType == outerClass?.starProjectedType
            }

            if (parentInfo != null && parentParameter == null) {
                if (parentInfo.parentParameter != null)
                    throw LocationRoutingException("Nested location '$locationClass' should have parameter for parent location because it is chained to its parent")
                if (parentInfo.pathParameters.any { !it.isOptional })
                    throw LocationRoutingException("Nested location '$locationClass' should have parameter for parent location because of non-optional path parameters ${parentInfo.pathParameters.filter { !it.isOptional }}")
                if (parentInfo.queryParameters.any { !it.isOptional })
                    throw LocationRoutingException("Nested location '$locationClass' should have parameter for parent location because of non-optional query parameters ${parentInfo.queryParameters.filter { !it.isOptional }}")
            }

            val pathParameterNames = RoutingPath.parse(path).parts
                .filter { it.kind == RoutingPathSegmentKind.Parameter }
                .map { PathSegmentSelectorBuilder.parseName(it.value) }

            val declaredParameterNames = declaredProperties.map { it.name }.toSet()
            val invalidParameters = pathParameterNames.filter { it !in declaredParameterNames }
            if (invalidParameters.any()) {
                throw LocationRoutingException("Path parameters '$invalidParameters' are not bound to '$locationClass' properties")
            }

            val pathParameters = declaredProperties.filter { it.name in pathParameterNames }
            val queryParameters =
                declaredProperties.filterNot { pathParameterNames.contains(it.name) || it == parentParameter }
            LocationInfo(locationClass, parentInfo, parentParameter, path, pathParameters, queryParameters)
        }
    }

    /**
     * Resolves parameters in a [call] to an instance of specified [locationClass].
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> resolve(locationClass: KClass<*>, call: ApplicationCall): T {
        return resolve(locationClass, call.parameters)
    }

    /**
     * Resolves [parameters] to an instance of specified [locationClass].
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> resolve(locationClass: KClass<*>, parameters: Parameters): T {
        return getOrCreateInfo(locationClass).create(parameters) as T
    }

    // TODO: optimize allocations
    private fun pathAndQuery(location: Any): ResolvedUriInfo {
        val info = getOrCreateInfo(location::class.java.kotlin)

        fun propertyValue(instance: Any, name: String): List<String> {
            // TODO: Cache properties by name in info
            val property = info.pathParameters.single { it.name == name }
            val value = property.getter.call(instance)
            return conversionService.toValues(value)
        }

        val substituteParts = RoutingPath.parse(info.path).parts.flatMap { it ->
            when (it.kind) {
                RoutingPathSegmentKind.Constant -> listOf(it.value)
                RoutingPathSegmentKind.Parameter -> {
                    if (info.klass.objectInstance != null)
                        throw IllegalArgumentException("There is no place to bind ${it.value} in object for '${info.klass}'")
                    propertyValue(location, PathSegmentSelectorBuilder.parseName(it.value))
                }
            }
        }

        val relativePath = substituteParts
            .filterNot { it.isEmpty() }
            .joinToString("/") { it.encodeURLQueryComponent() }

        val parentInfo = when {
            info.parent == null -> rootUri
            info.parentParameter != null -> {
                val enclosingLocation = info.parentParameter.getter.call(location)!!
                pathAndQuery(enclosingLocation)
            }
            else -> ResolvedUriInfo(info.parent.path, emptyList())
        }

        val queryValues = info.queryParameters.flatMap { property ->
            val value = property.getter.call(location)
            conversionService.toValues(value).map { property.name to it }
        }

        return parentInfo.combine(relativePath, queryValues)
    }

    /**
     * Constructs the url for [location].
     *
     * The class of [location] instance **must** be annotated with [Location].
     */
    fun href(location: Any): String {
        val info = pathAndQuery(location)
        return info.path + if (info.query.any())
            "?" + info.query.formUrlEncode()
        else
            ""
    }

    internal fun href(location: Any, builder: URLBuilder) {
        val info = pathAndQuery(location)
        builder.encodedPath = info.path
        for ((name, value) in info.query) {
            builder.parameters.append(name, value)
        }
    }

    private fun createEntry(parent: Route, info: LocationInfo): Route {
        val hierarchyEntry = info.parent?.let { createEntry(parent, it) } ?: parent
        return hierarchyEntry.createRouteFromPath(info.path)
    }

    /**
     * Creates all necessary routing entries to match specified [locationClass].
     */
    fun createEntry(parent: Route, locationClass: KClass<*>): Route {
        val info = getOrCreateInfo(locationClass)
        val pathRoute = createEntry(parent, info)

        return info.queryParameters.fold(pathRoute) { entry, query ->
            val selector = if (query.isOptional)
                OptionalParameterRouteSelector(query.name)
            else
                ParameterRouteSelector(query.name)
            entry.createChild(selector)
        }
    }

    /**
     * Configuration for [Locations].
     */
    class Configuration {
        /**
         * Specifies an alternative routing service. Default is [LocationAttributeRouteService].
         */
        var routeService: LocationRouteService? = null
    }

    /**
     * Installable feature for [Locations].
     */
    companion object Feature : ApplicationFeature<Application, Configuration, Locations> {
        override val key: AttributeKey<Locations> = AttributeKey("Locations")

        override fun install(pipeline: Application, configure: Configuration.() -> Unit): Locations {
            val configuration = Configuration().apply(configure)
            val routeService = configuration.routeService ?: LocationAttributeRouteService()
            return Locations(pipeline, routeService)
        }
    }
}

/**
 * Provides services for extracting routing information from a location class.
 */
interface LocationRouteService {
    /**
     * Retrieves routing information from a given [locationClass].
     * @return routing pattern, or null if a given class doesn't represent a route.
     */
    fun findRoute(locationClass: KClass<*>): String?
}

/**
 * Implements [LocationRouteService] by extracting routing information from a [Location] annotation.
 */
class LocationAttributeRouteService : LocationRouteService {
    private inline fun <reified T : Annotation> KAnnotatedElement.annotation(): T? {
        return annotations.singleOrNull { it.annotationClass == T::class } as T?
    }

    override fun findRoute(locationClass: KClass<*>): String? = locationClass.annotation<Location>()?.path
}

/**
 * Exception indicating that route parameters in curly brackets do not match class properties.
 */
class LocationRoutingException(message: String) : Exception(message)

@Suppress("KDocMissingDocumentation", "unused")
@Deprecated("Use LocationRoutingException instead",
    replaceWith = ReplaceWith("LocationRoutingException"), level = DeprecationLevel.ERROR)
typealias RoutingException = LocationRoutingException
