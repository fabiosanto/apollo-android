package com.apollographql.apollo3.compiler.ir

import com.apollographql.apollo3.api.BVariable
import com.apollographql.apollo3.api.BooleanExpression
import com.apollographql.apollo3.api.not
import com.apollographql.apollo3.ast.GQLBooleanValue
import com.apollographql.apollo3.ast.GQLDirective
import com.apollographql.apollo3.ast.GQLEnumTypeDefinition
import com.apollographql.apollo3.ast.GQLEnumValue
import com.apollographql.apollo3.ast.GQLEnumValueDefinition
import com.apollographql.apollo3.ast.GQLFloatValue
import com.apollographql.apollo3.ast.GQLFragmentDefinition
import com.apollographql.apollo3.ast.GQLInputObjectTypeDefinition
import com.apollographql.apollo3.ast.GQLInputValueDefinition
import com.apollographql.apollo3.ast.GQLIntValue
import com.apollographql.apollo3.ast.GQLInterfaceTypeDefinition
import com.apollographql.apollo3.ast.GQLListType
import com.apollographql.apollo3.ast.GQLListValue
import com.apollographql.apollo3.ast.GQLNamedType
import com.apollographql.apollo3.ast.GQLNode
import com.apollographql.apollo3.ast.GQLNonNullType
import com.apollographql.apollo3.ast.GQLNullValue
import com.apollographql.apollo3.ast.GQLObjectTypeDefinition
import com.apollographql.apollo3.ast.GQLObjectValue
import com.apollographql.apollo3.ast.GQLOperationDefinition
import com.apollographql.apollo3.ast.GQLScalarTypeDefinition
import com.apollographql.apollo3.ast.GQLSelection
import com.apollographql.apollo3.ast.GQLStringValue
import com.apollographql.apollo3.ast.GQLType
import com.apollographql.apollo3.ast.GQLUnionTypeDefinition
import com.apollographql.apollo3.ast.GQLValue
import com.apollographql.apollo3.ast.GQLVariableDefinition
import com.apollographql.apollo3.ast.GQLVariableValue
import com.apollographql.apollo3.ast.Schema
import com.apollographql.apollo3.ast.VariableReference
import com.apollographql.apollo3.ast.definitionFromScope
import com.apollographql.apollo3.ast.findDeprecationReason
import com.apollographql.apollo3.ast.findNonnull
import com.apollographql.apollo3.ast.findOptional
import com.apollographql.apollo3.ast.inferVariables
import com.apollographql.apollo3.ast.isApollo
import com.apollographql.apollo3.ast.isFieldNonNull
import com.apollographql.apollo3.ast.leafType
import com.apollographql.apollo3.ast.pretty
import com.apollographql.apollo3.ast.responseName
import com.apollographql.apollo3.ast.rootTypeDefinition
import com.apollographql.apollo3.ast.toUtf8
import com.apollographql.apollo3.ast.transform
import com.apollographql.apollo3.ast.validateAndCoerce
import com.apollographql.apollo3.compiler.MODELS_COMPAT
import com.apollographql.apollo3.compiler.MODELS_OPERATION_BASED
import com.apollographql.apollo3.compiler.MODELS_RESPONSE_BASED

internal class IrBuilder(
    private val schema: Schema,
    private val operationDefinitions: List<GQLOperationDefinition>,
    private val allFragmentDefinitions: Map<String, GQLFragmentDefinition>,
    private val alwaysGenerateTypesMatching: Set<String>,
    private val customScalarToKotlinName: Map<String, String>,
    codegenModels: String,
) : FieldMerger {
  private val usedEnums = mutableSetOf<String>()
  private val inputObjectsToGenerate = mutableListOf<String>()

  private val modelGroupBuilder: ModelGroupBuilder = when (codegenModels) {
    MODELS_RESPONSE_BASED -> ResponseBasedModelGroupBuilder(
        schema,
        allFragmentDefinitions,
        this
    )
    MODELS_OPERATION_BASED -> OperationBasedModelGroupBuilder(
        schema = schema,
        allFragmentDefinitions = allFragmentDefinitions,
        fieldMerger = this,
        insertFragmentSyntheticField = false,
        collectAllInlineFragmentFields = false,
        mergeTrivialInlineFragments = false,
    )
    MODELS_COMPAT -> OperationBasedModelGroupBuilder(
        schema = schema,
        allFragmentDefinitions = allFragmentDefinitions,
        fieldMerger = this,
        insertFragmentSyntheticField = true,
        collectAllInlineFragmentFields = true,
        mergeTrivialInlineFragments = true
    )
    else -> error("Unknown codegenModels: '$codegenModels'")
  }

  private fun shouldAlwaysGenerate(name: String) = alwaysGenerateTypesMatching.map { Regex(it) }.any { it.matches(name) }

  fun build(): Ir {
    val operations = operationDefinitions.map { it.toIr() }
    val fragments = allFragmentDefinitions.values.map { it.toIr() }

    val visitedInputObjects = mutableSetOf<String>()

    // Input objects contain (possible reentrant) references so build them before enums as they could add some
    val extraInputObjects = schema.typeDefinitions.values.filterIsInstance<GQLInputObjectTypeDefinition>()
        .map { it.name }
        .filter { shouldAlwaysGenerate(it) }
    inputObjectsToGenerate.addAll(extraInputObjects)
    val inputObjects = mutableListOf<IrInputObject>()
    while (inputObjectsToGenerate.isNotEmpty()) {
      val name = inputObjectsToGenerate.removeAt(0)
      if (visitedInputObjects.contains(name)) {
        continue
      }
      visitedInputObjects.add(name)
      inputObjects.add((schema.typeDefinition(name) as GQLInputObjectTypeDefinition).toIr())
    }

    val extraEnums = schema.typeDefinitions.values.filterIsInstance<GQLEnumTypeDefinition>()
        .map { it.name }
        .filter { shouldAlwaysGenerate(it) }
    val enums = (usedEnums + extraEnums).map { name ->
      (schema.typeDefinition(name) as GQLEnumTypeDefinition).toIr()
    }

    val customScalars = schema.typeDefinitions.values
        .filterIsInstance<GQLScalarTypeDefinition>()
        .filter { !it.isBuiltIn() }
        .map { it.toIr() }

    val objects = schema.typeDefinitions.values
        .filterIsInstance<GQLObjectTypeDefinition>()
        .filter { !it.isBuiltIn() }
        .map { it.toIr() }

    val interfaces = schema.typeDefinitions.values
        .filterIsInstance<GQLInterfaceTypeDefinition>()
        .filter { !it.isBuiltIn() }
        .map { it.toIr() }

    val unions = schema.typeDefinitions.values
        .filterIsInstance<GQLUnionTypeDefinition>()
        .filter { !it.isBuiltIn() }
        .map { it.toIr() }

    return Ir(
        operations = operations,
        fragments = fragments,
        inputObjects = inputObjects,
        enums = enums,
        customScalars = customScalars,
        objects = objects,
        interfaces = interfaces,
        unions = unions,
        allFragmentDefinitions = allFragmentDefinitions,
        schema = schema
    )
  }


  private fun GQLObjectTypeDefinition.toIr(): IrObject {
    return IrObject(
        name = name,
        implements = implementsInterfaces,
        description = description,
        deprecationReason = directives.findDeprecationReason()
    )
  }

  private fun GQLInterfaceTypeDefinition.toIr(): IrInterface {
    return IrInterface(
        name = name,
        implements = implementsInterfaces,
        description = description,
        deprecationReason = directives.findDeprecationReason()
    )
  }

  private fun GQLUnionTypeDefinition.toIr(): IrUnion {
    return IrUnion(
        name = name,
        members = memberTypes.map { it.name },
        description = description,
        deprecationReason = directives.findDeprecationReason()
    )
  }

  private fun GQLScalarTypeDefinition.toIr(): IrCustomScalar {
    return IrCustomScalar(
        name = name,
        kotlinName = customScalarToKotlinName[name],
        description = description,
        deprecationReason = directives.findDeprecationReason()
    )
  }

  private fun GQLInputObjectTypeDefinition.toIr(): IrInputObject {
    return IrInputObject(
        name = name,
        description = description,
        deprecationReason = directives.findDeprecationReason(),
        fields = inputFields.map { it.toIrInputField() }
    )
  }

  /**
   * This is not named `toIr` as [GQLInputValueDefinition] also maps to variables and arguments
   */
  private fun GQLInputValueDefinition.toIrInputField(): IrInputField {
    val coercedDefaultValue = defaultValue?.validateAndCoerce(type, schema)?.getOrThrow()

    var irType = type.toIr()
    if (type !is GQLNonNullType || coercedDefaultValue != null) {
      /**
       * Contrary to [IrVariable], we default to making input fields optional as they are out of control of the user and
       * we don't want to force users to fill input all values to define an input object
       */
      irType = irType.makeOptional()
    }
    return IrInputField(
        name = name,
        description = description,
        deprecationReason = directives.findDeprecationReason(),
        type = irType,
        defaultValue = coercedDefaultValue?.toIrValue(),
    )
  }

  private fun GQLEnumTypeDefinition.toIr(): IrEnum {
    return IrEnum(
        name = name,
        description = description,
        values = enumValues.map { it.toIr() }
    )
  }

  private fun GQLEnumValueDefinition.toIr(): IrEnum.Value {
    return IrEnum.Value(
        name = name,
        description = description,
        deprecationReason = directives.findDeprecationReason()
    )
  }

  /**
   * Strip any custom Apollo directive and format
   */
  private fun GQLNode.formatToString(): String {
    return transform {
      if (it is GQLDirective && it.isApollo()) {
        null
      } else {
        it
      }
    }!!.toUtf8()
  }

  private fun GQLOperationDefinition.toIr(): IrOperation {
    val typeDefinition = this.rootTypeDefinition(schema)
        ?: throw IllegalStateException("ApolloGraphql: cannot find root type for '$operationType'")

    check(name != null) {
      "Apollo doesn't support anonymous operation."
    }

    val usedFragments = usedFragments(
        schema = schema,
        allFragmentDefinitions,
        selections = selectionSet.selections,
        rawTypename = typeDefinition.name,
    )

    val sourceWithFragments = (formatToString() + "\n" + usedFragments.joinToString(
        separator = "\n"
    ) { fragmentName ->
      allFragmentDefinitions[fragmentName]!!.formatToString()
    }).trimEnd('\n')

    val dataModelGroup = modelGroupBuilder.buildOperationData(
        selections = selectionSet.selections,
        rawTypeName = typeDefinition.name,
        operationName = name!!
    )

    return IrOperation(
        name = name!!,
        description = description,
        operationType = operationType.toIrOperationType(),
        typeCondition = typeDefinition.name,
        variables = variableDefinitions.map { it.toIr() },
        selections = selectionSet.selections,
        sourceWithFragments = sourceWithFragments,
        filePath = sourceLocation.filePath!!,
        dataModelGroup = dataModelGroup
    )
  }

  private fun String.toIrOperationType() = when (this) {
    "query" -> IrOperationType.Query
    "mutation" -> IrOperationType.Mutation
    "subscription" -> IrOperationType.Subscription
    else -> error("unknown operation $this")
  }

  private fun GQLFragmentDefinition.toIr(): IrNamedFragment {
    val typeDefinition = schema.typeDefinition(typeCondition.name)

    val variableDefinitions = inferVariables(schema, allFragmentDefinitions)

    val interfaceModelGroup = modelGroupBuilder.buildFragmentInterface(
        fragmentName = name
    )

    val dataModelGroup = modelGroupBuilder.buildFragmentData(
        fragmentName = name
    )

    return IrNamedFragment(
        name = name,
        description = description,
        filePath = sourceLocation.filePath,
        typeCondition = typeDefinition.name,
        variables = variableDefinitions.map { it.toIr() },
        selections = selectionSet.selections,
        interfaceModelGroup = interfaceModelGroup,
        dataModelGroup = dataModelGroup,
    )
  }

  private fun VariableReference.toIr(): IrVariable {
    var type = expectedType.toIr()
    // This is an inferred variable from a fragment
    if (expectedType !is GQLNonNullType) {
      type = type.makeOptional()
    }
    return IrVariable(
        name = this.variable.name,
        defaultValue = null,
        type = type,
    )
  }

  private fun GQLVariableDefinition.toIr(): IrVariable {
    val coercedDefaultValue = defaultValue?.validateAndCoerce(type, schema)?.getOrThrow()

    var irType = type.toIr()
    // By default the GraphQL spec treats nullable types as optional variables but most of the times
    // users writing query variables want to give them a value so default to making them non-optional
    // and only make them optional if there is a defaultValue (which means the user has a use case for
    // not sending the value) or if it's opt-in explicitly through the @optional directive
    if (coercedDefaultValue != null || directives.findOptional()) {
      irType = irType.makeOptional()
    }
    return IrVariable(
        name = name,
        defaultValue = coercedDefaultValue?.toIrValue(),
        type = irType,
    )
  }

  /**
   * Maps to [IrType] and also keep tracks of what types are actually used so we only generate those
   */
  private fun GQLType.toIr(): IrType {
    return when (this) {
      is GQLNonNullType -> IrNonNullType(ofType = type.toIr())
      is GQLListType -> IrListType(ofType = type.toIr())
      is GQLNamedType -> when (schema.typeDefinition(name)) {
        is GQLScalarTypeDefinition -> {
          when (name) {
            "String" -> IrStringType
            "Boolean" -> IrBooleanType
            "Int" -> IrIntType
            "Float" -> IrFloatType
            "ID" -> IrIdType
            else -> {
              if (customScalarToKotlinName[name] != null) {
                IrCustomScalarType(name)
              } else {
                IrAnyType
              }
            }
          }
        }
        is GQLEnumTypeDefinition -> {
          usedEnums.add(name)
          IrEnumType(name = name)
        }
        is GQLInputObjectTypeDefinition -> {
          inputObjectsToGenerate.add(name)
          IrInputObjectType(name)
        }
        is GQLObjectTypeDefinition,
        is GQLInterfaceTypeDefinition,
        is GQLUnionTypeDefinition,
        -> IrModelType(IrUnknownModelId)
      }
    }
  }

  /**
   * An intermediate class used during collection
   */
  private class CollectedField(
      /**
       * All fields with the same response name should have the same infos here
       */
      val name: String,
      val alias: String?,

      val description: String?,
      val type: GQLType,
      val deprecationReason: String?,
      val forceNonNull: Boolean,
      val forceOptional: Boolean,

      /**
       * Merged field will merge their conditions and selectionSets
       */
      val condition: BooleanExpression<BVariable>,
      val selections: List<GQLSelection>,
  ) {
    val responseName = alias ?: name
  }

  override fun merge(fields: List<FieldWithParent>): List<MergedField> {
    return fields.map { fieldWithParent ->
      val gqlField = fieldWithParent.gqlField
      val typeDefinition = schema.typeDefinition(fieldWithParent.parentType)
      val fieldDefinition = gqlField.definitionFromScope(schema, typeDefinition)

      check(fieldDefinition != null) {
        "cannot find field definition for field '${gqlField.responseName()}' of type '${typeDefinition.name}'"
      }
      val forceNonNull = gqlField.directives.findNonnull() || typeDefinition.isFieldNonNull(gqlField.name)

      CollectedField(
          name = gqlField.name,
          alias = gqlField.alias,
          condition = gqlField.directives.toBooleanExpression(),
          selections = gqlField.selectionSet?.selections ?: emptyList(),
          type = fieldDefinition.type,
          description = fieldDefinition.description,
          deprecationReason = fieldDefinition.directives.findDeprecationReason(),
          forceNonNull = forceNonNull,
          forceOptional = gqlField.directives.findOptional(),
      )
    }.groupBy {
      it.responseName
    }.values.map { fieldsWithSameResponseName ->

      /**
       * Sanity checks, might be removed as this should be done during validation
       */
      check(fieldsWithSameResponseName.map { it.alias }.distinct().size == 1)
      check(fieldsWithSameResponseName.map { it.name }.distinct().size == 1)
      /**
       * The same field can have different descriptions in two different objects in which case
       * we should certainly use the interface description
       */
      // check(fieldsWithSameResponseName.map { it.description }.distinct().size == 1)
      check(fieldsWithSameResponseName.map { it.deprecationReason }.distinct().size == 1)
      // GQLTypes might differ because of their source location. Use pretty()
      // to canonicalize them
      check(fieldsWithSameResponseName.map { it.type }.distinctBy { it.pretty() }.size == 1)

      val first = fieldsWithSameResponseName.first()
      val childSelections = fieldsWithSameResponseName.flatMap { it.selections }

      val forceNonNull = fieldsWithSameResponseName.any {
        it.forceNonNull
      }
      val forceOptional = fieldsWithSameResponseName.any {
        it.forceOptional
      }
      var irType = first.type.toIr()
      if (forceNonNull) {
        irType = irType.makeNonNull()
      } else if (forceOptional) {
        irType = irType.makeNullable()
      }
      val info = IrFieldInfo(
          responseName = first.alias ?: first.name,
          description = first.description,
          deprecationReason = first.deprecationReason,
          type = irType,
      )

      MergedField(
          info = info,
          condition = BooleanExpression.Or(fieldsWithSameResponseName.map { it.condition }.toSet()).simplify(),
          selections = childSelections,
          rawTypeName = first.type.leafType().name,
      )
    }
  }
}

internal fun GQLValue.toIrValue(): IrValue {
  return when (this) {
    is GQLIntValue -> IrIntValue(value = value)
    is GQLStringValue -> IrStringValue(value = value)
    is GQLFloatValue -> IrFloatValue(value = value)
    is GQLBooleanValue -> IrBooleanValue(value = value)
    is GQLEnumValue -> IrEnumValue(value = value)
    is GQLNullValue -> IrNullValue
    is GQLVariableValue -> IrVariableValue(name = name)
    is GQLListValue -> IrListValue(values = values.map { it.toIrValue() })
    is GQLObjectValue -> IrObjectValue(
        fields = fields.map {
          IrObjectValue.Field(name = it.name, value = it.value.toIrValue())
        }
    )
  }
}

/**
 * This is guaranteed to return one of:
 * - True
 * - False
 * - (!)Variable
 * - (!)Variable & (!)Variable
 */
internal fun List<GQLDirective>.toBooleanExpression(): BooleanExpression<BVariable> {
  val conditions = mapNotNull {
    it.toBooleanExpression()
  }
  return if (conditions.isEmpty()) {
    BooleanExpression.True
  } else {
    check(conditions.toSet().size == conditions.size) {
      "ApolloGraphQL: duplicate @skip/@include directives are not allowed"
    }
    // Having both @skip and @include is allowed
    // In that case, it's equivalent to a "And"
    // See https://spec.graphql.org/draft/#sec--include
    BooleanExpression.And(conditions.toSet()).simplify()
  }
}

internal fun GQLDirective.toBooleanExpression(): BooleanExpression<BVariable>? {
  if (setOf("skip", "include").contains(name).not()) {
    // not a condition directive
    return null
  }
  if (arguments?.arguments?.size != 1) {
    throw IllegalStateException("ApolloGraphQL: wrong number of arguments for '$name' directive: ${arguments?.arguments?.size}")
  }

  val argument = arguments!!.arguments.first()

  return when (val value = argument.value) {
    is GQLBooleanValue -> {
      if (value.value) BooleanExpression.True else BooleanExpression.False
    }
    is GQLVariableValue -> BooleanExpression.Element(BVariable(name = value.name)).let {
      if (name == "skip") not(it) else it
    }
    else -> throw IllegalStateException("ApolloGraphQL: cannot pass ${value.toUtf8()} to '$name' directive")
  }
}

internal fun IrFieldInfo.maybeNullable(makeNullable: Boolean): IrFieldInfo {
  if (!makeNullable) {
    return this
  }

  return copy(
      type = type.makeNullable()
  )
}

internal fun IrType.replacePlaceholder(newId: IrModelId): IrType {
  return when (this) {
    is IrNonNullType -> IrNonNullType(ofType = ofType.replacePlaceholder(newId))
    is IrListType -> IrListType(ofType = ofType.replacePlaceholder(newId))
    is IrModelType -> copy(id = newId)
    else -> error("Not a compound type?")
  }
}
