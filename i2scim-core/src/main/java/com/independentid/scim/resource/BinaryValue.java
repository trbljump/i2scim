/*
 * Copyright 2021.  Independent Identity Incorporated
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.independentid.scim.resource;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.independentid.scim.protocol.RequestCtx;
import com.independentid.scim.schema.Attribute;
import com.independentid.scim.schema.SchemaException;
import com.independentid.scim.serializer.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Base64.Encoder;

/**
 * This Value type handles SCIM Binary Value types which are base64 encoded values.
 * @author pjdhunt
 */
public class BinaryValue extends Value {

    static final Decoder decoder = Base64.getDecoder();
    static final Encoder encoder = Base64.getEncoder();

    byte[] value;


    public BinaryValue(Attribute attr, JsonNode node) throws SchemaException, ParseException {
        super(attr, node);
        parseJson(node);
    }

    /**
     * @param attr The {@link Attribute} type definition for the value.
     * @param bval The unencoded value as an array of byte.
     */
    public BinaryValue(Attribute attr, byte[] bval) {
        this.value = bval;
        this.jtype = JsonNodeType.BINARY;
        this.attr = attr;
    }

    public BinaryValue(Attribute attr, String b64string) {
        this.value = decoder.decode(b64string.getBytes());
        this.jtype = JsonNodeType.BINARY;
        this.attr = attr;
    }

    @Override
    public void serialize(JsonGenerator gen, RequestCtx ctx) throws IOException {
        gen.writeString(encoder.encodeToString(this.value));

    }

    @Override
    public void parseJson(JsonNode node)
            throws SchemaException, ParseException {
        //The value should be actually a base64 encoded string (DER)
        if (node == null)
            throw new SchemaException("Was expecting a JSON string (Base64 encoded) value but encountered null");

        this.value = decoder.decode(node.asText().getBytes(StandardCharsets.UTF_8));

        //TODO:  SHould the DER Value encoding be validated?
		
		/*
		if (!node.isBinary())
			throw new SchemaException("Expecting binary value for attribute "+attr.getPath()+", received node endpoint: "+this.jtype);
		try {
			this.value = node.binaryValue();
		} catch (IOException e) {
			throw new SchemaException("Unknown IO Exception occurred parsing binary value.",e);
		}
		*/

    }

    @Override
    public JsonNode toJsonNode(ObjectNode parent, String aname) {
        if (parent == null)
            parent = JsonUtil.getMapper().createObjectNode();
        parent.put(aname, toString());
        return parent;
    }

    /**
     * Returns the unencoded raw binary as byte[]
     */
    @Override
    public byte[] getRawValue() {
        return this.value;
    }

    /**
     * Returns the base64 encoded binary value
     */
    public String toString() {
        return encoder.encodeToString(this.value);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof BinaryValue) {
            BinaryValue obVal = (BinaryValue) obj;
            return Arrays.equals(value, obVal.value);
        }
        return false;
    }

    @Override
    public int compareTo(Value o) {
        if (o instanceof BinaryValue) {
            BinaryValue obVal = (BinaryValue) o;
            return Arrays.compare(value, obVal.value);
        }
        throw new ClassCastException("Unable to compare Value types");
    }
}
