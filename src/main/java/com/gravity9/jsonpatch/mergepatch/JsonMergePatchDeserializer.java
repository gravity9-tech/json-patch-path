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

package com.gravity9.jsonpatch.mergepatch;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

final class JsonMergePatchDeserializer extends JsonDeserializer<JsonMergePatch> {

	@Override
	public JsonMergePatch deserialize(final JsonParser jp,
									  final DeserializationContext ctxt) throws IOException {
		ObjectMapper mapper = new ObjectMapper().setConfig(ctxt.getConfig());
		jp.setCodec(mapper);
		final JsonNode node = jp.readValueAsTree();

		/*
		 * Not an object: the simple case
		 */
		if (!node.isObject()) {
			return new NonObjectMergePatch(node);
		}

		/*
		 * The complicated case...
		 *
		 * We have to build a set of removed members, plus a map of modified
		 * members.
		 */

		final Set<String> removedMembers = new HashSet<>();
		final Map<String, JsonMergePatch> modifiedMembers = new HashMap<>();
		final Iterator<Map.Entry<String, JsonNode>> iterator = node.fields();

		Map.Entry<String, JsonNode> entry;

		while (iterator.hasNext()) {
			entry = iterator.next();
			if (entry.getValue().isNull())
				removedMembers.add(entry.getKey());
			else {
				final JsonMergePatch value
					= deserialize(entry.getValue().traverse(), ctxt);
				modifiedMembers.put(entry.getKey(), value);
			}
		}

		return new ObjectMergePatch(removedMembers, modifiedMembers);
	}

	/*
	 * This method MUST be overriden... The default is to return null, which is
	 * not what we want.
	 */
	@Override
	@SuppressWarnings("deprecation")
	public JsonMergePatch getNullValue() {
		return new NonObjectMergePatch(NullNode.getInstance());
	}
}
