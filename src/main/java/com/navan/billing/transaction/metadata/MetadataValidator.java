package com.navan.billing.transaction.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.navan.billing.common.ApiException;
import com.navan.billing.transaction.TransactionType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Checks that `metadata` matches the typed model for the transaction's type
 * (flight -> FlightMetadata, hotel -> HotelMetadata, ...). Anything that does not fit is a 400,
 * so a flight can never be stored as a hotel blob or as an empty object.
 * The original JSON (including any extra fields) is what gets stored.
 */
@Component
public class MetadataValidator {
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public MetadataValidator(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public void validate(TransactionType type, JsonNode metadata) {
        if (metadata == null || !metadata.isObject() || metadata.isEmpty()) {
            throw ApiException.badRequest("metadata must be a non-empty JSON object");
        }
        Class<?> model = switch (type) {
            case FLIGHT -> FlightMetadata.class;
            case HOTEL -> HotelMetadata.class;
            case RAIL -> RailMetadata.class;
            case NAVAN_FEE -> NavanFeeMetadata.class;
        };
        Object parsed = parse(type, metadata, model);
        Set<ConstraintViolation<Object>> violations = validator.validate(parsed);
        if (!violations.isEmpty()) {
            List<String> errors = violations.stream()
                    .map(v -> "metadata." + v.getPropertyPath() + ": " + v.getMessage())
                    .sorted()
                    .toList();
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_metadata",
                    "metadata is not valid for type " + type.wireName(), Map.of("errors", errors));
        }
        if (parsed instanceof HotelMetadata hotel && !hotel.checkOut().isAfter(hotel.checkIn())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_metadata",
                    "metadata.check_out must be after metadata.check_in");
        }
    }

    private Object parse(TransactionType type, JsonNode metadata, Class<?> model) {
        try {
            return objectMapper.treeToValue(metadata, model);
        } catch (InvalidFormatException e) {
            if (type == TransactionType.NAVAN_FEE && e.getTargetType() == NavanFeeMetadata.FeeKind.class) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "unknown_fee_kind",
                        "metadata.fee_kind must be one of: trip_fee, agent_call_fee");
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_metadata",
                    "metadata has a field with the wrong format: " + e.getPathReference());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_metadata",
                    "metadata does not match the " + type.wireName() + " model");
        }
    }
}
