package com.muse.meomuneum.musicrecord.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;

class KakaoReverseGeocodingClientTest {
    private static final URI ENDPOINT = URI.create("https://dapi.kakao.com/v2/local/geo/coord2regioncode.json");
    private final HttpClient http = mock(HttpClient.class);
    private final HttpResponse<String> response = mock(HttpResponse.class);
    private final KakaoReverseGeocodingClient client =
            new KakaoReverseGeocodingClient(new ObjectMapper(), http, ENDPOINT, "dummy-test-key");

    @Test
    void legalRegionWinsEvenWhenAdministrativeRegionAppearsFirst() throws Exception {
        stub(200, """
                {"documents":[
                  {"region_type":"H","region_1depth_name":"서울특별시","region_2depth_name":"마포구"},
                  {"region_type":"B","region_1depth_name":"서울특별시","region_2depth_name":"성동구"}
                ]}
                """);

        assertThat(client.reverseGeocode(37.5, 127.0)).isEqualTo("서울 성동구");
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any());
        assertThat(request.getValue().uri().getQuery()).isEqualTo("x=127.0&y=37.5&input_coord=WGS84");
        assertThat(request.getValue().headers().firstValue("Authorization"))
                .hasValue("KakaoAK dummy-test-key");
        assertThat(request.getValue().uri().toString()).doesNotContain("dummy-test-key");
    }

    @Test
    void administrativeRegionIsUsedOnlyWhenLegalRegionIsAbsent() throws Exception {
        stub(200, """
                {"documents":[{"region_type":"H","region_1depth_name":"경기도",
                               "region_2depth_name":"성남시 분당구"}]}
                """);
        assertThat(client.reverseGeocode(37.5, 127.0)).isEqualTo("경기도 성남시 분당구");
    }

    @Test
    void incompleteLegalRegionDoesNotFallBackToAdministrativeRegion() throws Exception {
        stub(200, """
                {"documents":[
                  {"region_type":"B","region_1depth_name":"서울특별시"},
                  {"region_type":"H","region_1depth_name":"서울특별시","region_2depth_name":"성동구"}
                ]}
                """);
        assertBadRequest();
    }

    @Test
    void nonTextRegionFieldsAreRejected() throws Exception {
        stub(200, """
                {"documents":[{"region_type":"B","region_1depth_name":123,
                               "region_2depth_name":true}]}
                """);
        assertReason("kakao_reverse_geocoding_region_incomplete");
    }

    @Test
    void malformedOrEmptyResponseIsBadRequest() throws Exception {
        stub(200, "not-json");
        assertReason("kakao_reverse_geocoding_invalid_json");
        stub(200, "{\"documents\":[]}");
        assertReason("kakao_reverse_geocoding_location_not_found");
        stub(200, "null");
        assertReason("kakao_reverse_geocoding_invalid_body");
    }

    @Test
    void nonSuccessHttpStatusesAreBadRequest() throws Exception {
        for (int status : new int[] {401, 429, 500}) {
            stub(status, "{}");
            assertBadRequest();
        }
    }

    @Test
    void transportFailureIsBadRequest() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("network unavailable"));
        assertReason("kakao_reverse_geocoding_transport_failed");
    }

    @Test
    void timeoutHasItsOwnInternalReason() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpTimeoutException("test timeout"));
        assertReason("kakao_reverse_geocoding_timeout");
    }

    @Test
    void interruptionRestoresThreadFlag() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("test interrupted"));
        try {
            assertReason("kakao_reverse_geocoding_interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void missingKeyDoesNotMakeHttpRequest() throws Exception {
        var noKey = new KakaoReverseGeocodingClient(new ObjectMapper(), http, ENDPOINT, "");
        assertThatThrownBy(() -> noKey.reverseGeocode(37.5, 127.0))
                .isInstanceOf(MusicRecordException.class);
        verify(http, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private void stub(int status, String body) throws Exception {
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        doReturn(response).when(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private void assertBadRequest() {
        assertThatThrownBy(() -> client.reverseGeocode(37.5, 127.0))
                .isInstanceOf(MusicRecordException.class)
                .hasMessage("invalid request");
    }

    private void assertReason(String reason) {
        assertThatThrownBy(() -> client.reverseGeocode(37.5, 127.0))
                .isInstanceOf(MusicRecordException.class)
                .hasMessage("invalid request")
                .satisfies(error -> assertThat(((MusicRecordException) error).reason()).isEqualTo(reason));
    }
}
