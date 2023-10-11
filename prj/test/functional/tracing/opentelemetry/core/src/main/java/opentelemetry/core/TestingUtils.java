/*
 * Copyright (c) 2023, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package opentelemetry.core;

import com.google.protobuf.InvalidProtocolBufferException;

import com.oracle.coherence.common.base.Blocking;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;

import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;


import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.mockserver.integration.ClientAndServer;

import org.mockserver.model.Body;
import org.mockserver.model.HttpRequest;

import static org.mockserver.integration.ClientAndServer.startClientAndServer;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Testing utilities.
 *
 * @author rl 9.22.2023
 * @since 24.03
 */
public class TestingUtils
    {
    /**
     * Create a mock server bound to the specified port.
     *
     * @param nPort  the listen port
     *
     * @return a mock server bound to the specified port
     */
    public static ClientAndServer createServer(int nPort)
        {
        ClientAndServer server = startClientAndServer(nPort);
        server.when(request()).respond(response().withStatusCode(200));

        return server;
        }

    /**
     * Extract spans and return a {@link List} of {@link Span}s.
     *
     * @param server  the mock server
     *
     * @return a {@link List} of {@link Span}s recorded by the mock server
     */
    public static List<Span> extractSpans(ClientAndServer server)
        {
        HttpRequest[] requests = server.retrieveRecordedRequests(request());

        return Arrays.stream(requests)
                .map(HttpRequest::getBody)
                .flatMap(body -> getExportTraceServiceRequest(body).stream())
                .flatMap(r -> r.getResourceSpansList().stream())
                .flatMap(r -> r.getScopeSpansList().stream())
                .flatMap(r -> r.getSpansList().stream())
                .collect(Collectors.toList());

        }

    /**
     * Create an {@link ExportTraceServiceRequest} from the provided body.
     *
     * @param body  the request body
     *
     * @return {@link ExportTraceServiceRequest}, if any, from the provided
     *         body
     */
    public static Optional<ExportTraceServiceRequest> getExportTraceServiceRequest(Body<?> body)
        {
        try
            {
            return Optional.ofNullable(ExportTraceServiceRequest.parseFrom(body.getRawBytes()));
            }
        catch (InvalidProtocolBufferException e)
            {
            return Optional.empty();
            }
        }

    /**
     * Return a list of {@link Span spans} for the specified operations that
     * were generated by the specified member.
     *
     * @param listSpans  the {@link Span spans} to filter
     * @param aOpNames   the {@link Span spans} operations names
     *
     * @return a list of {@link Span spans} for the specified operations that
     *         were generated by the specified member
     */
    public static List<Span> getSpans(List<Span> listSpans, String[] aOpNames)
        {
        return getSpans(listSpans, aOpNames, -1);
        }

    /**
     * Return a list of {@link Span spans} for the specified operations that
     * were generated by the specified member.
     *
     * @param listSpans  the {@link Span spans} to filter
     * @param aOpNames   the {@link Span spans} operations names
     * @param nMember    the member which generated the {@link Span spans}
     *
     * @return a list of {@link Span spans} for the specified operations that
     *         were generated by the specified member
     */
    public static List<Span> getSpans(List<Span> listSpans, String[] aOpNames, long nMember)
        {
        if (aOpNames == null || aOpNames.length == 0)
            {
            throw new IllegalArgumentException("aOpNames cannot be null or empty");
            }

        // build an or-based predicate using provided operation names
        Predicate<String> predicate = Arrays.stream(aOpNames)
                .map(s -> (Predicate<String>) s::equals)
                .reduce(Predicate::or).orElseThrow();

        if (nMember < 1)
            {
            return listSpans.stream()
                    .filter(span -> predicate.test(span.getName()))
                    .toList();
            }
        else
            {
            // filter and return the spans
            return listSpans.stream()
                    .filter(span -> predicate.test(span.getName()))
                    .filter(span -> getMetadata(span).get("member").getIntValue() == nMember)
                    .toList();
            }
        }

    /**
     * Obtain a {@link Map map} of the metadata associated with the provided
     * {@link Span span}.
     *
     * @param span  the span from which metadata is to be extracted
     *
     * @return a {@link Map map} of the metadata associated with the provided
     *         {@link Span span}
     */
    public static Map<String, AnyValue> getMetadata(Span span)
        {
        return span.getAttributesList().stream()
                .collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
        }

    /**
     * Waits for the {@code OpenTelemetry} runtime to complete exporting
     * {@link Span spans}.
     *
     * @return a {@link List list} of all exported {@link Span spans}.
     */
    public static List<Span> waitForAllSpans(ClientAndServer server)
        {
        int cLast = 0;
        int cAttempts = 1;
        int cMaxAttempts = 10;

        for (int i = 0; i < 50; i++)
            {
            List<Span> listSpans = TestingUtils.extractSpans(server);
            int cCurrent = listSpans.size();
            if (cLast == cCurrent)
                {
                if (cAttempts == cMaxAttempts)
                    {
                    return listSpans;
                    }
                cAttempts++;
                }
            else
                {
                cAttempts = 0;
                }
            cLast = cCurrent;
            try
                {
                Blocking.sleep(500);
                }
            catch (InterruptedException e)
                {
                throw new RuntimeException(e);
                }
            }
        throw new IllegalStateException("Spans still being produced after 50 attempts to check for a stable count");
        }

    /**
     * Enums for expected cache event mutation types.
     */
    @SuppressWarnings("unused")
    public enum MutationType
        {
            /**
             * MapEvent insert.
             */
            inserted,

            /**
             * MapEvent update.
             */
            updated,

            /**
             * MapEvent deleted.
             */
            deleted,

            /**
             * No event.
             */
            none,
        }
    }
