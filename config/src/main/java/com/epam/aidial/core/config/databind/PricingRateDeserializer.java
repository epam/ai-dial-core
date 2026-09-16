package com.epam.aidial.core.config.databind;

import com.epam.aidial.core.config.Condition;
import com.epam.aidial.core.config.PricingRate;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;

/**
 * Accepts either a plain rate string (the flat, pre-existing shape) or a decision-tree node
 * object ({@code {test, ifTrue, ifFalse}}), producing a unified {@link PricingRate}.
 */
public class PricingRateDeserializer extends JsonDeserializer<PricingRate> {

    @Override
    public PricingRate deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        if (p.getCurrentToken() == JsonToken.VALUE_STRING) {
            String rate = p.getValueAsString();
            try {
                Double.parseDouble(rate);
            } catch (NumberFormatException e) {
                throw InvalidFormatException.from(p, "Expected a JSON string with a valid double", rate, PricingRate.class);
            }
            PricingRate pricingRate = new PricingRate();
            pricingRate.setRate(rate);
            return pricingRate;
        }

        if (p.getCurrentToken() == JsonToken.START_OBJECT) {
            Condition test = null;
            PricingRate ifTrue = null;
            PricingRate ifFalse = null;
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String name = p.getCurrentName();
                p.nextToken();
                switch (name) {
                    case "test" -> test = p.readValueAs(Condition.class);
                    case "ifTrue" -> ifTrue = deserialize(p, ctx);
                    case "ifFalse" -> ifFalse = deserialize(p, ctx);
                    default -> p.skipChildren();
                }
            }
            if (test == null) {
                return ctx.reportInputMismatch(PricingRate.class, "A decision-tree node requires a \"test\" field");
            }
            PricingRate pricingRate = new PricingRate();
            pricingRate.setTest(test);
            pricingRate.setIfTrue(ifTrue);
            pricingRate.setIfFalse(ifFalse);
            return pricingRate;
        }

        if (p.getCurrentToken() == JsonToken.VALUE_NULL) {
            return null;
        }

        return ctx.reportInputMismatch(PricingRate.class,
                "Expected a rate string or a decision-tree node object, got %s", p.getCurrentToken());
    }
}
