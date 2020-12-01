/*
 * Copyright (c) 2018 Swisscom (Switzerland) Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.swisscom.cloud.sb.broker.util

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.junit.WireMockRule
import com.github.tomakehurst.wiremock.stubbing.Scenario
import com.swisscom.cloud.sb.test.httpserver.HttpServerApp
import com.swisscom.cloud.sb.test.httpserver.HttpServerConfig
import org.apache.http.NoHttpResponseException
import org.apache.http.client.HttpRequestRetryHandler
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler
import org.apache.http.protocol.HttpContext
import org.junit.ClassRule
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestTemplate
import spock.lang.Ignore
import spock.lang.Specification

class RestTemplateBuilderTest extends Specification {
    private static final Logger LOG = LoggerFactory.getLogger(RestTemplateBuilderTest.class);
    private static final int http_port = 36000
    private static final int https_port = 36001

    @ClassRule
    public static WireMockRule wireMockRule

    def setupSpec() {
        wireMockRule = new WireMockRule()
        wireMockRule.start()
    }

    def cleanupSpec() {
        wireMockRule.stop()
    }

    def "restTemplate with no features enabled"() {
        given:
        HttpServerApp httpServer = new HttpServerApp().startServer(HttpServerConfig.create(http_port))
        when:
        def response = makeGetRequest(new RestTemplateBuilder().build())
        then:
        response.statusCode == HttpStatus.OK
        response.body.equalsIgnoreCase('hello')
        cleanup:
        httpServer?.stop()
    }

    def "restTemplate with basic auth"() {
        given:
        String username = 'aUsername'
        String password = 'aPassword'
        HttpServerApp httpServer = new HttpServerApp().startServer(HttpServerConfig.create(http_port).withSimpleHttpAuthentication(username, password))
        when:
        def response = makeGetRequest(new RestTemplateBuilder().withBasicAuthentication(username, password).build())
        then:
        response.statusCode == HttpStatus.OK
        response.body.equalsIgnoreCase('hello')
        cleanup:
        httpServer?.stop()
    }

    @Ignore
    def "restTemplate with digest"() {
        given:
        String username = 'aUsername'
        String password = 'aPassword'
        HttpServerApp httpServer = new HttpServerApp().startServer(HttpServerConfig.create(http_port).withDigestAuthentication(username, password))
        when:
        def response = makeGetRequest(new RestTemplateBuilder().withDigestAuthentication(username, password).build())
        then:
        response.statusCode == HttpStatus.OK
        response.body.equalsIgnoreCase('hello')
        cleanup:
        httpServer?.stop()
    }

    def "restTemplate with Bearer Token"() {
        given:
        String token = 'AbCdEf123456'
        HttpServerApp httpServer = new HttpServerApp().startServer(HttpServerConfig.create(http_port).withBearerAuthentication(token))
        when:
        def response = makeGetRequest(new RestTemplateBuilder().withBearerAuthentication(token).build())
        then:
        response.statusCode == HttpStatus.OK
        response.body.equalsIgnoreCase('hello')
        cleanup:
        httpServer?.stop()
    }

    def 'GET request over https to self signed certificate endpoint throws exception'() {
        given:
        HttpServerApp httpServer = new HttpServerApp().startServer(HttpServerConfig.create(http_port).withHttpsPort(https_port)
                .withKeyStore(this.getClass().getResource('/server-keystore.jks').file, 'secret', 'secure-server'))
        when:
        def response = makeHttpsGetRequest(new RestTemplateBuilder().build())
        then:
        Exception ex = thrown(Exception)
        ex.cause.toString().contains('SSLHandshakeException')
        cleanup:
        httpServer?.stop()
    }

    def 'GET request over https to self signed certificate endpoint works when SSL checking is disabled'() {
        given:
        HttpServerApp httpServer = new HttpServerApp().startServer(HttpServerConfig.create(http_port).withHttpsPort(https_port)
                .withKeyStore(this.getClass().getResource('/server-keystore.jks').file, 'secret', 'secure-server'))
        when:
        def response = makeHttpsGetRequest(new RestTemplateBuilder().withSSLValidationDisabled().build())
        then:
        response.statusCode == HttpStatus.OK
        response.body.equalsIgnoreCase('hello')
        cleanup:
        httpServer?.stop()
    }

    def 'GET request over https with a server that expects a client side certificate in pcks #12'() {
        given:
        HttpServerApp httpServer = new HttpServerApp().startServer(HttpServerConfig.create(http_port).withHttpsPort(https_port)
                .withKeyStore(this.getClass().getResource('/server-keystore.jks').file, 'secret', 'secure-server')
                .withTrustStore(this.getClass().getResource('/server-truststore.jks').file,
                        'secret'))
        when:
        def response = makeHttpsGetRequest(new RestTemplateBuilder().withSSLValidationDisabled().withClientSideCertificate(new File(this.getClass().getResource('/client.crt').file).text,
                new File(this.getClass().getResource('/client.key').file).text).build())

        then:
        response.statusCode == HttpStatus.OK
        response.body.equalsIgnoreCase('hello')
        cleanup:
        httpServer?.stop()
    }

    def 'GET request over https with a server that expects a client side certificate in pcks #12 should fail when certificate don\'t match'() {
        given:
        LOG.info("show the keystore:")
        //LOG.info(new File(HttpServerApp.class.getResource('/anotherkeystore').file).getText('UTF-8'))
        HttpServerApp httpServer = new HttpServerApp().startServer(HttpServerConfig.create(http_port).withHttpsPort(https_port)
                .withKeyStore(this.getClass().getResource('/test-server-keystore.jks').file, 'secret', 'secure-server')
                .withTrustStore(this.getClass().getResource('/test-server-truststore.jks').file, 'secret'))

        when:
        def response = makeHttpsGetRequest(new RestTemplateBuilder().withSSLValidationDisabled().withClientSideCertificate(new File(this.getClass().getResource('/client.crt').file).text,
                new File(this.getClass().getResource('/client.key').file).text).build())

        then:
        Exception ex = thrown(Exception)
        ex
        cleanup:
        httpServer?.stop()
    }

    def 'GET request over https to self signed certificate endpoint fails when SSL checking is disabled but hostname is wrong'() {
        given:
        HttpServerApp httpServer = new HttpServerApp().startServer(HttpServerConfig.create(http_port).withHttpsPort(https_port)
                .withKeyStore(this.getClass().getResource('/server-keystore.jks').file, 'secret', 'secure-server'))
        when:
        def response = makeHttpsGetRequestTo(new RestTemplateBuilder().withSSLValidationDisabled().build(), "127.0.0.1")
        then:
        Exception ex = thrown(Exception)
        ex
        cleanup:
        httpServer?.stop()
    }

    def 'GET request over https to self signed certificate endpoint with wrong hostname works when SSL and Hostname checking is disabled'() {
        given:
        HttpServerApp httpServer = new HttpServerApp().startServer(HttpServerConfig.create(http_port).withHttpsPort(https_port)
                .withKeyStore(this.getClass().getResource('/server-keystore.jks').file, 'secret', 'secure-server'))
        when:
        def response = makeHttpsGetRequestTo(new RestTemplateBuilder().withSSLValidationDisabled().withHostNameVerificationDisabled().build(), "127.0.0.1")
        then:
        response.statusCode == HttpStatus.OK
        response.body.equalsIgnoreCase('hello')
        cleanup:
        httpServer?.stop()
    }

    def 'PUT request with I/O error'() {
        given: "setup wiremock with connection reset error"
        wireMockRule.stubFor(WireMock.put(WireMock.urlEqualTo("/ioerror"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(WireMock.aResponse().withFault(Fault.EMPTY_RESPONSE))
                .willSetStateTo("Cause Success"))

        wireMockRule.stubFor(WireMock.put(WireMock.urlEqualTo("/ioerror"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs("Cause Success")
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "text/plain")
                        .withBody("Hello world!")))

        and: "setup retry handler"
        HttpRequestRetryHandler retryHandler = new DefaultHttpRequestRetryHandler(3, true, new ArrayList<Class<? extends IOException>>()) {
            @Override
            boolean retryRequest(IOException exception, int executionCount, HttpContext context) {
                if (exception instanceof NoHttpResponseException && executionCount < 3) {
                    return super.retryRequest(exception, executionCount, context);
                }
                return false
            }
        }
        RestTemplate restTemplate =  new RestTemplateBuilder().withRetryHandler(retryHandler).withDigestAuthentication("test", "test").build()

        when:
        def response = restTemplate.exchange(wireMockRule.baseUrl() + "/ioerror", HttpMethod.PUT, new HttpEntity("HELLO"), String.class)

        then:
        response.statusCode == HttpStatus.OK
        response.body.equalsIgnoreCase('Hello world!')
    }

    private def makeGetRequest(RestTemplate template) {
        return template.exchange("http://localhost:${http_port}", HttpMethod.GET, new HttpEntity(), String.class)
    }


    private def makeHttpsGetRequest(RestTemplate template) {
        return template.exchange("https://localhost:${https_port}", HttpMethod.GET, new HttpEntity(), String.class)
    }

    private def makeHttpsGetRequestTo(RestTemplate template, String hostname) {
        return template.exchange("https://${hostname}:${https_port}", HttpMethod.GET, new HttpEntity(), String.class)
    }
}