/*
 * Copyright (c) 2014, Francis Galiegue (fgaliegue@gmail.com)
 *
 * This software is dual-licensed under:
 *
 * - the Lesser General Public License (LGPL) version 3.0 or, at your option, any
 *   later version;
 * - the Apache Software License (ASL) version 2.0.
 *
 * The text of this file and of both licenses is available at the root of this
 * project or, if you have the jar distribution, in directory META-INF/, under
 * the names LGPL-3.0.txt and ASL-2.0.txt respectively.
 *
 * Direct link to the sources:
 *
 * - LGPL 3.0: https://www.gnu.org/licenses/lgpl-3.0.txt
 * - ASL 2.0: http://www.apache.org/licenses/LICENSE-2.0.txt
 */

package com.gravity9.jsonpatch.diff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.fge.jackson.JacksonUtils;
import com.github.fge.jackson.NodeType;
import com.github.fge.jackson.jsonpointer.JsonPointer;
import com.github.fge.msgsimple.bundle.MessageBundle;
import com.github.fge.msgsimple.load.MessageBundles;
import com.gravity9.jsonpatch.JsonPatch;
import com.gravity9.jsonpatch.JsonPatchException;
import com.gravity9.jsonpatch.JsonPatchMessages;
import com.gravity9.jsonpatch.JsonPatchOperation;
import com.gravity9.jsonpatch.RemoveOperation;
import com.gravity9.jsonpatch.jackson.JsonNumEquals;
import com.jayway.jsonpath.PathNotFoundException;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * JSON "diff" implementation
 *
 * <p>This class generates a JSON Patch (as in, an RFC 6902 JSON Patch) given
 * two JSON values as inputs. The patch can be obtained directly as a {@link
 * JsonPatch} or as a {@link JsonNode}.</p>
 *
 * <p>Note: there is <b>no guarantee</b> about the usability of the generated
 * patch for any other source/target combination than the one used to generate
 * the patch.</p>
 *
 * <p>This class always performs operations in the following order: removals,
 * additions and replacements. It then factors removal/addition pairs into
 * move operations, or copy operations if a common element exists, at the same
 * {@link JsonPointer pointer}, in both the source and destination.</p>
 *
 * <p>You can obtain a diff either as a {@link JsonPatch} directly or, for
 * backwards compatibility, as a {@link JsonNode}.</p>
 *
 * @since 1.2
 */
@ParametersAreNonnullByDefault
public final class JsonDiff {

	private static final MessageBundle BUNDLE
		= MessageBundles.getBundle(JsonPatchMessages.class);
	private static final ObjectMapper MAPPER = JacksonUtils.newMapper();

	private static final JsonNumEquals EQUIVALENCE
		= JsonNumEquals.getInstance();

	private static final String NULL_ARGUMENT_KEY = "common.nullArgument";

	private JsonDiff() {
	}

	/**
	 * Generate a JSON patch for transforming the source node into the target
	 * node
	 *
	 * @param source the node to be patched
	 * @param target the expected result after applying the patch
	 * @return the patch as a {@link JsonPatch}
	 * @since 1.9
	 */
	public static JsonPatch asJsonPatch(final JsonNode source,
										final JsonNode target) {
		BUNDLE.checkNotNull(source, NULL_ARGUMENT_KEY);
		BUNDLE.checkNotNull(target, NULL_ARGUMENT_KEY);
		final Map<JsonPointer, JsonNode> unchanged
			= getUnchangedValues(source, target);
		final DiffProcessor processor = new DiffProcessor(unchanged);

		generateDiffs(processor, JsonPointer.empty(), source, target);
		return processor.getPatch();
	}

	/**
	 * Generate a JSON patch for transforming the source node into the target
	 * node ignoring given fields
	 *
	 * @param source the node to be patched
	 * @param target the expected result after applying the patch
	 * @param fieldsToIgnore list of JsonPath or JsonPointer paths which should be ignored when generating diff. Non-existing fields are ignored.
	 * @return the patch as a {@link JsonPatch}
	 * @throws JsonPatchException if fieldsToIgnored not in valid JsonPath or JsonPointer format
	 * @since 2.0.0
	 */
	public static JsonPatch asJsonPatchIgnoringFields(final JsonNode source,
										final JsonNode target, final List<String> fieldsToIgnore) throws JsonPatchException {
		BUNDLE.checkNotNull(source, NULL_ARGUMENT_KEY);
		BUNDLE.checkNotNull(target, NULL_ARGUMENT_KEY);
		final List<JsonPatchOperation> ignoredFieldsRemoveOperations = getJsonPatchRemoveOperationsForIgnoredFields(fieldsToIgnore);

		JsonNode sourceWithoutIgnoredFields = removeIgnoredFields(source, ignoredFieldsRemoveOperations);
		JsonNode targetWithoutIgnoredFields = removeIgnoredFields(target, ignoredFieldsRemoveOperations);

		final Map<JsonPointer, JsonNode> unchanged
				= getUnchangedValues(sourceWithoutIgnoredFields, targetWithoutIgnoredFields);
		final DiffProcessor processor = new DiffProcessor(unchanged);

		generateDiffs(processor, JsonPointer.empty(), sourceWithoutIgnoredFields, targetWithoutIgnoredFields);
		return processor.getPatch();
	}

	private static JsonNode removeIgnoredFields(JsonNode node, List<JsonPatchOperation> ignoredFieldsRemoveOperations) throws JsonPatchException {
		JsonNode nodeWithoutIgnoredFields = node;
		for (JsonPatchOperation operation : ignoredFieldsRemoveOperations) {
			nodeWithoutIgnoredFields = removeIgnoredFieldOrIgnore(nodeWithoutIgnoredFields, operation);
		}
		return nodeWithoutIgnoredFields;
	}

	private static JsonNode removeIgnoredFieldOrIgnore(JsonNode nodeWithoutIgnoredFields, JsonPatchOperation operation) throws JsonPatchException {
		try {
			List<JsonPatchOperation> operationsList = new ArrayList<>();
			operationsList.add(operation);
			return new JsonPatch(operationsList).apply(nodeWithoutIgnoredFields);
		} catch (JsonPatchException e) {
			// If remove for specific path throws PathNotFound, it means that node does not contain specific field which should be ignored.
			// See more `empty patch if object does not contain ignored field` in diff.json file.
			if (e.getCause() instanceof PathNotFoundException) {
				return nodeWithoutIgnoredFields;
			}
			throw e;
		}
	}

	/**
	 * Generate a JSON patch for transforming the source node into the target
	 * node
	 *
	 * @param source the node to be patched
	 * @param target the expected result after applying the patch
	 * @return the patch as a {@link JsonNode}
	 * @throws JsonPatchException when cannot generate JSON diff
	 */
	public static JsonNode asJson(final JsonNode source, final JsonNode target) throws JsonPatchException {
		final String s;
		try {
			s = MAPPER.writeValueAsString(asJsonPatch(source, target));
			return MAPPER.readTree(s);
		} catch (IOException e) {
			throw new JsonPatchException("cannot generate JSON diff", e);
		}
	}

	/**
	 * Generate a JSON patch for transforming the source node into the target
	 * node ignoring given fields
	 *
	 * @param source the node to be patched
	 * @param target the expected result after applying the patch
	 * @param fieldsToIgnore list of JsonPath or JsonPointer paths which should be ignored when generating diff. Non-existing fields are ignored.
	 * @return the patch as a {@link JsonNode}
	 * @throws JsonPatchException if fieldsToIgnored not in valid JsonPath or JsonPointer format
	 * @since 2.0.0
	 */
	public static JsonNode asJsonIgnoringFields(final JsonNode source, final JsonNode target, List<String> fieldsToIgnore) throws JsonPatchException {
		final String s;
		try {
			s = MAPPER.writeValueAsString(asJsonPatchIgnoringFields(source, target, fieldsToIgnore));
			return MAPPER.readTree(s);
		} catch (IOException e) {
			throw new JsonPatchException("cannot generate JSON diff", e);
		}
	}

	private static void generateDiffs(final DiffProcessor processor,
									  final JsonPointer pointer, final JsonNode source, final JsonNode target) {
		if (EQUIVALENCE.equivalent(source, target))
			return;

		final NodeType firstType = NodeType.getNodeType(source);
		final NodeType secondType = NodeType.getNodeType(target);

		/*
		 * Node types differ: generate a replacement operation.
		 */
		if (firstType != secondType) {
			processor.valueReplaced(pointer, source, target);
			return;
		}

		/*
		 * If we reach this point, it means that both nodes are the same type,
		 * but are not equivalent.
		 *
		 * If this is not a container, generate a replace operation.
		 */
		if (!source.isContainerNode()) {
			processor.valueReplaced(pointer, source, target);
			return;
		}

		/*
		 * If we reach this point, both nodes are either objects or arrays
		 * delegate.
		 */
		if (firstType == NodeType.OBJECT)
			generateObjectDiffs(processor, pointer, (ObjectNode) source,
				(ObjectNode) target);
		else // array
			generateArrayDiffs(processor, pointer, (ArrayNode) source,
				(ArrayNode) target);
	}

	private static void generateObjectDiffs(final DiffProcessor processor,
											final JsonPointer pointer, final ObjectNode source,
											final ObjectNode target) {
		final Set<String> firstFields
			= collect(source.fieldNames(), new TreeSet<>());
		final Set<String> secondFields
			= collect(target.fieldNames(), new TreeSet<>());

		final Set<String> copy1 = new HashSet<>(firstFields);
		copy1.removeAll(secondFields);

		for (final String field : Collections.unmodifiableSet(copy1))
			processor.valueRemoved(pointer.append(field), source.get(field));

		final Set<String> copy2 = new HashSet<>(secondFields);
		copy2.removeAll(firstFields);


		for (final String field : Collections.unmodifiableSet(copy2))
			processor.valueAdded(pointer.append(field), target.get(field));

		final Set<String> intersection = new HashSet<>(firstFields);
		intersection.retainAll(secondFields);

		for (final String field : intersection)
			generateDiffs(processor, pointer.append(field), source.get(field),
				target.get(field));
	}

	private static <T> Set<T> collect(Iterator<T> from, Set<T> to) {
		while (from.hasNext()) {
			to.add(from.next());
		}
		return Collections.unmodifiableSet(to);
	}


	private static void generateArrayDiffs(final DiffProcessor processor,
										   final JsonPointer pointer, final ArrayNode source,
										   final ArrayNode target) {
		final int firstSize = source.size();
		final int secondSize = target.size();
		final int size = Math.min(firstSize, secondSize);

		/*
		 * Source array is larger; in this case, elements are removed from the
		 * target; the index of removal is always the original arrays's length.
		 */
		for (int index = size; index < firstSize; index++)
			processor.valueRemoved(pointer.append(size), source.get(index));

		for (int index = 0; index < size; index++)
			generateDiffs(processor, pointer.append(index), source.get(index),
				target.get(index));

		// Deal with the destination array being larger...
		for (int index = size; index < secondSize; index++)
			processor.valueAdded(pointer.append("-"), target.get(index));
	}


	static Map<JsonPointer, JsonNode> getUnchangedValues(final JsonNode source,
														 final JsonNode target) {
		final Map<JsonPointer, JsonNode> ret = new HashMap<>();
		computeUnchanged(ret, JsonPointer.empty(), source, target);
		return ret;
	}

	private static void computeUnchanged(final Map<JsonPointer, JsonNode> ret,
										 final JsonPointer pointer, final JsonNode first, final JsonNode second) {
		if (EQUIVALENCE.equivalent(first, second)) {
			ret.put(pointer, second);
			return;
		}

		final NodeType firstType = NodeType.getNodeType(first);
		final NodeType secondType = NodeType.getNodeType(second);

		if (firstType != secondType)
			return; // nothing in common

		// We know they are both the same type, so...

		switch (firstType) {
			case OBJECT:
				computeObject(ret, pointer, first, second);
				break;
			case ARRAY:
				computeArray(ret, pointer, first, second);
				break;
			default:
				/* nothing */
		}
	}

	private static void computeObject(final Map<JsonPointer, JsonNode> ret,
									  final JsonPointer pointer, final JsonNode source,
									  final JsonNode target) {
		final Iterator<String> firstFields = source.fieldNames();

		String name;

		while (firstFields.hasNext()) {
			name = firstFields.next();
			if (!target.has(name))
				continue;
			computeUnchanged(ret, pointer.append(name), source.get(name),
				target.get(name));
		}
	}

	private static void computeArray(final Map<JsonPointer, JsonNode> ret,
									 final JsonPointer pointer, final JsonNode source, final JsonNode target) {
		final int size = Math.min(source.size(), target.size());

		for (int i = 0; i < size; i++)
			computeUnchanged(ret, pointer.append(i), source.get(i),
				target.get(i));
	}

	private static List<JsonPatchOperation> getJsonPatchRemoveOperationsForIgnoredFields(List<String> fieldsToIgnore) {
		final List<JsonPatchOperation> ignoredFieldsRemoveOperations = new ArrayList<>();
		for (String fieldToIgnore : fieldsToIgnore) {
			ignoredFieldsRemoveOperations.add(new RemoveOperation(fieldToIgnore));
		}
		return ignoredFieldsRemoveOperations;
	}

}
