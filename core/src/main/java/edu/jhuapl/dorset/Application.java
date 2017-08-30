/*
 * Copyright 2016 The Johns Hopkins University Applied Physics Laboratory LLC
 * All rights reserved.
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
package edu.jhuapl.dorset;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.jhuapl.dorset.ResponseStatus.Code;
import edu.jhuapl.dorset.agents.Agent;
import edu.jhuapl.dorset.agents.AgentRequest;
import edu.jhuapl.dorset.agents.AgentResponse;
import edu.jhuapl.dorset.filters.RequestFilter;
import edu.jhuapl.dorset.filters.ResponseFilter;
import edu.jhuapl.dorset.reporting.NullReporter;
import edu.jhuapl.dorset.reporting.Report;
import edu.jhuapl.dorset.reporting.Reporter;
import edu.jhuapl.dorset.routing.Router;
import edu.jhuapl.dorset.sessions.Session;
import edu.jhuapl.dorset.sessions.SessionObject;
import edu.jhuapl.dorset.sessions.SessionService;
import edu.jhuapl.dorset.users.User;

/**
 * Dorset Application
 * <p>
 * The application manages the state of the Dorset framework. An application
 * processes requests and returns responses. The requests are handled by agents.
 * A router determines which agent handles a request. Each request-response
 * cycle can be stored as a report for further analysis.
 *
 * A basic application has at least one agent, a router, and methods for getting
 * requests and sending responses:
 * 
 * <pre>
 * Agent agent = new CalculatorAgent();
 * Router router = new SingleAgentRouter(agent);
 * Application app = new Application(router);
 * 
 * while (true) {
 *     Request request = yourMethodToGetRequests();
 *     Response response = app.process(request);
 *     yourMethodToSendResponse(response);
 * }
 * </pre>
 */
public class Application {
    private final Logger logger = LoggerFactory.getLogger(Application.class);

    protected Agent[] agents;
    protected Router router;
    protected Reporter reporter;
    protected User user;
    protected SessionService sessionService;
    protected List<RequestFilter> requestFilters;
    protected List<ResponseFilter> responseFilters;
    protected List<ShutdownListener> shutdownListeners;

    /**
     * Create a Dorset application
     * <p>
     * Uses a null reporter that ignores new reports.
     *
     * @param router  a router that finds the appropriate agent for a request
     */
    public Application(Router router) {
        this(router, new NullReporter());
    }

    /**
     * Create a Dorset application
     *
     * @param router  a router that finds the appropriate agent for a request
     * @param reporter  a reporter which logs request handling
     */
    public Application(Router router, Reporter reporter) {
        this.router = router;
        this.reporter = reporter;
        this.agents = router.getAgents();
        this.requestFilters = new ArrayList<RequestFilter>();
        this.responseFilters = new ArrayList<ResponseFilter>();
        this.shutdownListeners = new ArrayList<ShutdownListener>();
    }

    /**
     * Get the active agents in the registry
     *
     * @return array of Agent objects
     */
    public Agent[] getAgents() {
        return agents;
    }

    /**
     * Add a request filter
     *
     * @param filter  a RequestFilter
     */
    public void addRequestFilter(RequestFilter filter) {
        requestFilters.add(filter);
    }

    /**
     * Add a response filter
     *
     * @param filter  a ResponseFilter
     */
    public void addResponseFilter(ResponseFilter filter) {
        responseFilters.add(filter);
    }

    /**
     * Add a shutdown listener
     *
     * @param listener  a listener that runs when shutdown() is called
     */
    public void addShutdownListener(ShutdownListener listener) {
        shutdownListeners.add(listener);
    }

    /**
     * Set a User for a single user Dorset application
     *
     * @param user  the User of a single user application
     */
    public void setUser(User user) {
        this.user = user;
    }
    
    /**
     * Set a SessionService to handle sessions for a Dorset application
     *
     * @param sessionService  a SessionService
     * 
     */
    public void setSessionService(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * Process a request
     *
     * @param request  Request object
     * @return Response object
     */
    public Response process(Request request) {
        
        // Check if session exists? -- check from the request !  
        // If no Session create new Session
        // If Session exists, pull session information to pass
        
        Session currentSession = null; // initialize currentSession 
        SessionObject sessionObject = new SessionObject();
        String sessionId = "";
        if (request.getSession() != null) { // check if it is an ongoing session in the Request
            currentSession = request.getSession(); 
            sessionId = currentSession.getId();
        } else {
            sessionId = this.sessionService.create();
            currentSession = this.sessionService.getSession(sessionId);
        }
                 
  
        logger.info("Processing request: " + request.getText());
        
        sessionObject.setRequestId(request.getId());
        sessionObject.setRequest(request);
        
        for (RequestFilter rf : requestFilters) {
            request = rf.filter(request);  
            sessionObject.setRequest(request); // replace request with filtered request
        }
        Response response = new Response(new ResponseStatus(
                Code.NO_AVAILABLE_AGENT));
        Report report = new Report(request);

        long startTime = System.nanoTime();
        Agent[] agents = router.route(request); // do we want to include this information in the session as well? 
                
        report.setRouteTime(startTime, System.nanoTime());
        if (agents.length > 0) {
            response = new Response(new ResponseStatus(
                    Code.NO_RESPONSE_FROM_AGENT));
            startTime = System.nanoTime();
            for (Agent agent : agents) {
                report.setAgent(agent);
                AgentResponse agentResponse = null;
                
                AgentRequest agentRequest = new AgentRequest(request.getText(), this.user); // Create AgentRequest
                agentRequest.setSession(currentSession); // Set the session in the AgentRequest
                agentResponse = agent.process(agentRequest); // Process AgentRequest
                
                if (agentResponse != null) {
                    
                    // Get agentResponse and check for dialog and remove from session service 
                    // Set Response in the session object
                    // Check the state of the Status and handle accordingly --> move this to the SessionService 
                    response = new Response(agentResponse);
                    sessionObject.setResponse(response); 
                    
                    this.sessionService.update(sessionId, sessionObject);
                    
                    break;
                }
            }
            report.setAgentTime(startTime, System.nanoTime());
        }
        report.setResponse(response);
        reporter.store(report); // should we also have an additional store session service? or embed session in report?

        return response;
    }

    /**
     * Call this when the application is done running
     */
    public void shutdown() {
        for (ShutdownListener listener : shutdownListeners) {
            listener.shutdown();
        }
    }

}
