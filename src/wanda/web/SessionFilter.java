/* ===========================================================================
 * Copyright (C) 2017 CapsicoHealth Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package wanda.web;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import tilda.db.Connection;
import tilda.db.ConnectionPool;
import tilda.utils.AnsiUtil;
import tilda.utils.DateTimeUtil;
import tilda.utils.DurationUtil;
import tilda.utils.HttpStatus;
import tilda.utils.SystemValues;
import wanda.data.AccessLog_Data;
import wanda.data.AccessLog_Factory;
import wanda.data.TenantUser_Data;
import wanda.data.TenantUser_Factory;
import wanda.data.Tenant_Data;
import wanda.data.UserDetail_Data;
import wanda.data.UserDetail_Factory;
import wanda.data.User_Data;
import wanda.data.User_Factory;
import wanda.servlets.helpers.RoleHelper;
import wanda.web.config.WebBasics;
import wanda.web.exceptions.ResourceNotAuthorizedException;


public class SessionFilter implements javax.servlet.Filter
  {
    protected static final Logger LOG = LogManager.getLogger(SessionFilter.class.getName());

    @Override
    public void init(FilterConfig arg0)
    throws ServletException
      {
        LOG.info("");
        LOG.info("");
        LOG.info("*************************************************************************************************************************************");
        WebBasics.autoInit();
        ConnectionPool.autoInit();
        LOG.info("*************************************************************************************************************************************");
        LOG.info("");
        LOG.info("");
      }

    @Override
    public void destroy()
      {
      }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
    throws IOException, ServletException
      {
        long T0 = System.nanoTime();
        HttpServletRequest Request = (HttpServletRequest) req;
        HttpServletResponse Response = (HttpServletResponse) res;
        RequestUtil Req = new RequestUtil(Request);

        Connection MasterConnection = null;
        User_Data MasterDbUser = null;
        AccessLog_Data AL = null;

        boolean skipRollback = false;

        // Multi Tenant
        Connection TenantConnection = null;
        boolean isMultiTenant = false;
        boolean isMasterPath = isMasterPath(Request);
        boolean isAuthPassthrough = isAuthPassthrough(Request);

        try
          {
            AL = LogRequestHeader(Request);
            MasterConnection = ConnectionPool.get("MAIN");
            MasterDbUser = getUser(Request, MasterConnection);

            isMultiTenant = ConnectionPool.isMultiTenant();

            if (Request.getScheme().equals("https") == false)
              {
                LOG.error("The server only accepts HTTPS requests.");
                throw new ServletException("The server only accepts HTTPS requests.");
              }

            if (MasterDbUser == null)
              {
                if (isAuthPassthrough == false)
                  {
                    Response.sendError(HttpStatus.Unauthorized._Code, "Unauthenticated session");
                    throw new ServletException("Unauthenticated session");
                  }
              }
            else
              {
                // Is user locked?
                if (isUserLocked(MasterDbUser))
                  {
                    Req.removeSessionUser();
                    Response.sendError(HttpStatus.Unauthorized._Code, "Unauthorized User");
                    throw new ServletException("Unauthorized User");
                  }
                UserDetail_Data UD = getUserDetail(MasterConnection, MasterDbUser, Response);
                MasterDbUser.setUserDetail(UD);
              }


            /* ******* Multi Tenant Logic ******* */
            User_Data TenantDbUser = null;
            TenantUser_Data TenantUser = null;
            Tenant_Data Tenant = null;

            if (isMultiTenant)
              {
                TenantUser = getTenantUser(Request, MasterConnection);
                if (TenantUser == null)
                  {
                    if (isAuthPassthrough == false)
                      {
                        if (MasterDbUser != null && !MasterDbUser.hasRoles(RoleHelper.SUPERADMIN))
                          {
                            LOG.info("Unable to load TenantUser from Session.");
                            SessionUtil.InvalidateSession(Request);
                            Response.sendError(HttpStatus.Unauthorized._Code, "Unauthenticated session");
                            throw new ServletException("Unauthenticated session");
                          }
                      }
                  }
                else
                  {
                    Tenant = TenantUser.getTenant(MasterConnection);
                    if (Tenant.getActive() == false)
                      {
                        Req.removeSessionUser();
                        throw new ResourceNotAuthorizedException("Tenant", Tenant.getRefnum() + "", "Tenant '" + Tenant.getName() + "' is inactive. Please contact your administrator");
                      }
                    TenantConnection = ConnectionPool.get(Tenant.getConnectionId());
                    TenantDbUser = getUser(Request, TenantConnection);
                    // SimpleServlet Subclasses use U.getPerson()
                    UserDetail_Data TenantDbPerson = getUserDetail(TenantConnection, TenantDbUser, Response);
                    TenantDbUser.setUserDetail(TenantDbPerson);
                    // NOTE: Assuming TenantDbUser will not be null
                    // Why?: Because we sync User_Data to TenantConnection in Login Servlet
                  }
              }

            int EulaClear = Req.getSessionInt(SessionUtil.Attributes.EULA_CLEAR.name());
            if (EulaClear == SystemValues.EVIL_VALUE)
              {
                if (isAuthPassthrough == false)
                  {
                    LOG.info("User not cleared for EULA.");
                    SessionUtil.InvalidateSession(Request);
                    Response.sendError(HttpStatus.Unauthorized._Code, "Unauthenticated session");
                    throw new ServletException("Unauthenticated session");
                  }
              }

            // Skip for SuperAdmin
            if (MasterDbUser != null && MasterDbUser.hasRoles(RoleHelper.SUPERADMIN))
              {
                Request.setAttribute(RequestUtil.Attributes.CONNECTION.toString(), MasterConnection);
                Request.setAttribute(RequestUtil.Attributes.USER.toString(), MasterDbUser);
              }
            else
              {
                Request.setAttribute(RequestUtil.Attributes.CONNECTION.toString(),
                isMultiTenant ? (isMasterPath ? MasterConnection : TenantConnection) : MasterConnection);
                Request.setAttribute(RequestUtil.Attributes.USER.toString(), isMultiTenant ? (isMasterPath ? MasterDbUser : TenantDbUser) : MasterDbUser);
              }

            // LOG.info("********************************************************************************************************************************************\n");
            Response.setHeader("X-Frame-Options", "SAMEORIGIN");
            chain.doFilter(req, res);
            if (Response.getStatus() != 200 && Response.getStatus() != 302)
              throw new Exception("Servlet error " + Response.getStatus());
            // Clear flag from Session, after Success Request
            // So that subsequent requests are not affected
            skipRollback = isSkipRollback(Req);
            boolean forceReloadUser = Req.getSessionInt(SessionUtil.Attributes.FORCE_RELOAD_USER.name()) == SessionUtil.FORCE_RELOAD_USER;
            if (forceReloadUser)
              {
                Req.removeSessionAttribute(SessionUtil.Attributes.FORCE_RELOAD_USER.name());
                MasterDbUser = getUser(Request, MasterConnection);
              }
            if (Req.getServletPath().equals("/svc/Login") == true)
              AL.setLogin(true);
            writeAccessLogs(MasterConnection, MasterDbUser, AL, Response);
            if (TenantConnection != null)
              TenantConnection.commit();
            if (MasterConnection != null)
              MasterConnection.commit();// TO Write ACCESS LOGS INTO MASTER DB
            LOG.info("\n"
            + "   ********************************************************************************************************************************************\n"
            + "   ** " + AnsiUtil.NEGATIVE + "R E Q U E S T  #" + AL.getRefnum() + "  S U C C E E D E D" + AnsiUtil.NEGATIVE_OFF + ": " + Request.getRequestURL() + "\n"
            + "   ********************************************************************************************************************************************");
          }
        catch (Throwable T)
          {
            LOG.error(AnsiUtil.NEGATIVE + ">>>>>>>>>>>>>>>" + AnsiUtil.NEGATIVE_OFF + "  R E Q U E S T  #" + (AL == null ? "NULL" : AL.getRefnum()) + "  F A I L E D  " + AnsiUtil.NEGATIVE + "<<<<<<<<<<<<<<<" + AnsiUtil.NEGATIVE_OFF);
            LOG.error("**    in " + DurationUtil.printDurationMilliSeconds(System.nanoTime() - T0) + ".");
            LOG.catching(T);
            if (T.getCause() != null)
              LOG.catching(T.getCause());
            skipRollback = isSkipRollback(Req);
            try
              {
                if (Req.getServletPath().equals("/svc/Login") == true)
                  AL.setLogin(false);
                writeAccessLogs(MasterConnection, MasterDbUser, AL, Response, T, skipRollback);
                if (MasterConnection != null)
                  MasterConnection.commit();// TO Write ACCESS LOGS INTO MASTER DB
                if (TenantConnection != null && skipRollback == false)
                  TenantConnection.rollback();
              }
            catch (Throwable T1)
              {
                LOG.error(AnsiUtil.NEGATIVE + ">>>>>>>>>>>>>>>" + AnsiUtil.NEGATIVE_OFF + "  R E Q U E S T  #" + (AL == null ? "NULL" : AL.getRefnum()) + "  F A I L E D  " + AnsiUtil.NEGATIVE + "<<<<<<<<<<<<<<<" + AnsiUtil.NEGATIVE_OFF);
                LOG.error("**    in " + DurationUtil.printDurationMilliSeconds(System.nanoTime() - T0) + ".");
                LOG.catching(T);
                if (T.getCause() != null)
                  LOG.catching(T.getCause());
                try
                  {
                    if (MasterConnection != null)
                      MasterConnection.rollback();
                  }
                catch (SQLException me)
                  {
                    LOG.error("Exception in MasterConnection rollback\n", me);
                  }
                try
                  {
                    if (TenantConnection != null)
                      TenantConnection.rollback();
                  }
                catch (SQLException te)
                  {
                    LOG.error("Exception in MasterConnection rollback\n", te);
                  }
              }
          }
        finally
          {
            try
              {
                if (MasterConnection != null)
                  MasterConnection.close();
              }
            catch (SQLException e)
              {
                LOG.error("Unable to close MasterConnection\n", e);
              }
            try
              {
                if (TenantConnection != null)
                  TenantConnection.close();
              }
            catch (SQLException e)
              {
                LOG.error("Unable to close TenantConnection\n", e);
              }
            LOG.info(SystemValues.NEWLINEx4);
          }
      }

    private void writeAccessLogs(Connection MasterConnection, User_Data MasterDbUser, AccessLog_Data AL,
    HttpServletResponse Response, Throwable T, boolean skipRollback)
    throws Exception
      {
        // LOG.error(SystemValues.NEWLINEx2);
        if (AL == null)
          {
            throw new Exception("AccessLog_Data AL is null, cannot write Access log to DB ");
          }
        if (MasterConnection == null)
          {
            throw new Exception("Connection MasterConnection is null, cannot write Access log to DB ");
          }
        AL.setResponseStatus(T.getMessage());
        if (T instanceof ResourceNotAuthorizedException)
          {
            AL.setUserEmail(((ResourceNotAuthorizedException) T)._ResourceId);
          }

        if (skipRollback == false)
          {
            MasterConnection.rollback();
          }
        writeAccessLogs(MasterConnection, MasterDbUser, AL, Response);
      }

    private void writeAccessLogs(Connection MasterConnection, User_Data MasterDbUser, AccessLog_Data AL, HttpServletResponse Response)
    throws Exception
      {
        if (AL == null)
          {
            throw new Exception("AccessLog_Data AL is null, cannot write Access log to DB ");
          }
        if (MasterConnection == null)
          {
            throw new Exception("Connection MasterConnection is null, cannot write Access log to DB ");
          }
        if (MasterDbUser != null)
          {
            AL.setUser_rn(MasterDbUser.getRefnum());
            AL.setUserEmail(MasterDbUser.getEmail());
          }
        AL.setResponseCode((short)Response.getStatus());
        if (AL.write(MasterConnection) == false)
          throw new Exception("Cannot create a AccessLog record in the database");
      }

    private AccessLog_Data LogRequestHeader(HttpServletRequest Request)
    throws Exception
      {
        LOG.info(SystemValues.NEWLINEx2);
        AccessLog_Data AL = AccessLog_Factory.create(SessionUtil.getSession(Request).getId());
        AL.setIpAddress(Request.getRemoteAddr() + ":" + Request.getRemotePort());
        AL.setUrl(Request.getRequestURL().toString());
        AL.setServlet(Request.getServletPath());
        String Str = getRequestHeaderLogStr(Request, AL, true);
        LOG.info(Str);
        return AL;
      }

    public static String getRequestHeaderLogStr(HttpServletRequest Request, AccessLog_Data AL, boolean LineMarkers)
    throws UnsupportedEncodingException, Exception
      {
        StringBuilder Str = new StringBuilder();
        if (LineMarkers == true)
          {
            Str.append("\n");
            Str.append("   ********************************************************************************************************************************************\n");
          }
        Str.append("   ***  " + AnsiUtil.NEGATIVE + "R E Q U E S T   #" + (AL == null ? "NULL" : AL.getRefnum()) + AnsiUtil.NEGATIVE_OFF + " - " + DateTimeUtil.printDateTime(ZonedDateTime.now()) + "\n");
        Str.append("   ***  RequestURL     : " + Request.getRequestURL().toString() + "\n");
        Str.append("   ***  RemoteAddr     : " + Request.getRemoteAddr() + ":" + Request.getRemotePort() + "\n");
        Str.append("   ***  PathInfo/Trans : " + Request.getPathInfo() + " | " + Request.getPathTranslated() + "\n");
        Str.append("   ***  Servlet/CtxPath: " + Request.getServletPath() + " | " + Request.getContextPath() + "\n");
        Str.append("   ***  Headers:\n");
        Enumeration<String> HeaderNames = Request.getHeaderNames();
        while (HeaderNames.hasMoreElements())
          {
            String Name = HeaderNames.nextElement();
            Enumeration<String> Headers = Request.getHeaders(Name);
            while (Headers.hasMoreElements())
              Str.append("   ***    " + Name + ": " + Headers.nextElement() + "\n");
          }

        StringBuilder Params = new StringBuilder();
        Str.append("   ***  Parameters:\n");
        Enumeration<String> ParamNames = Request.getParameterNames();
        while (ParamNames.hasMoreElements() == true)
          {
            String p = (String) ParamNames.nextElement();
            String[] Vals = Request.getParameterValues(p);
            for (String v : Vals)
              {
                if (p.matches("(?i)" + _MaskedNVPRegex) == true)
                  v = "****";
                Str.append("   ***    " + p + "= " + v + "\n");
                if (Params.length() != 0)
                  Params.append("&");
                Params.append(p).append("=").append(URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8.toString()));
              }
          }
        if (AL != null)
          AL.setParameters(Params.toString());
        if (LineMarkers == true)
          Str.append("   ********************************************************************************************************************************************");
        return Str.toString();
      }

    private boolean isSkipRollback(RequestUtil Req)
      {
        boolean skipRollback = Req.getSessionInt(SessionUtil.Attributes.FORCE_COMMIT.name()) == SessionUtil.FORCE_COMMIT;
        if (skipRollback)
          {
            Req.removeSessionAttribute(SessionUtil.Attributes.FORCE_COMMIT.name());
          }
        return skipRollback;
      }

    public static String Clean(String QueryString)
      {
        return QueryString == null ? "" : QueryString.replaceAll("(?i)(" + _MaskedNVPRegex + ")=[^&]+", "$1=****");
      }

    protected static User_Data getUser(HttpServletRequest Request, Connection C)
    throws Exception
      {
        HttpSession S = SessionUtil.getSession(Request);
        double sessionDurationMinutes = DurationUtil.getDurationMinutes(DurationUtil.NANOSECS_PER_MILLISECOND * (System.currentTimeMillis() - S.getCreationTime()));
        if (sessionDurationMinutes >= WebBasics.getForceReLoginMins())
          {
            SessionUtil.InvalidateSession(Request);
            S = SessionUtil.getSession(Request);
          }
        Long UserRefNum = (Long) S.getAttribute(SessionUtil.Attributes.USERREFNUM.toString());
        if (UserRefNum != null)
          {
            User_Data U = User_Factory.lookupByPrimaryKey(UserRefNum);
            if (U.read(C) == false)
              {
                LOG.error("Cannot read session user '" + UserRefNum + "' from the database! Cancelling the session and restarting a new one.");
                SessionUtil.InvalidateSession(Request);
                return null;
              }
            LOG.info("**    User: " + U.getRefnum() + "/" + U.getId() + " (" + U.getEmail() + ")");
            return U;
          }
        LOG.info("No session User");
        return null;
      }

    protected static TenantUser_Data getTenantUser(HttpServletRequest Request, Connection C)
    throws Exception
      {
        HttpSession S = SessionUtil.getSession(Request);
        Long TenantUserRefnum = (Long) S.getAttribute(SessionUtil.Attributes.TENANTUSERREFNUM.toString());

        if (TenantUserRefnum != null)
          {
            TenantUser_Data TU = TenantUser_Factory.lookupByActive(TenantUserRefnum);
            if (TU.read(C) == false)
              {
                LOG.error("Cannot read session TenantUser '" + TenantUserRefnum + "' from the database! Cancelling the session and restarting a new one.");
                return null;
              }
            LOG.info("**    TenantUser: " + TU.getRefnum());
            return TU;
          }
        return null;
      }

    private static Set<String> _MaskedUrlNameValuePairs = new HashSet<String>();
    private static String      _MaskedNVPRegex          = "";

    public synchronized static void addMaskedUrlNvp(String NVPName)
      {
        if (_MaskedUrlNameValuePairs.add(NVPName) == true)
          {
            StringBuilder Str = new StringBuilder();
            for (String s : _MaskedUrlNameValuePairs)
              {
                if (Str.length() != 0)
                  Str.append("|");
                Str.append(Pattern.quote(s));
              }
            _MaskedNVPRegex = Str.toString();
          }
      }

    // Helpers
    private boolean isAuthPassthrough(HttpServletRequest Request)
      {
        Iterator<String> I = WebBasics.getAuthPassthroughs();
        while (I.hasNext() == true)
          {
            String u = I.next();
            if (Request.getServletPath().endsWith(u) == true)
              return true;
          }
        return false;
      }

    private boolean isMasterPath(HttpServletRequest Request)
      {
        Iterator<String> I = WebBasics.getMasterPaths();
        while (I.hasNext() == true)
          {
            String u = I.next();
            if (Request.getServletPath().endsWith(u) == true)
              return true;
          }
        return false;
      }

    private UserDetail_Data getUserDetail(Connection C, User_Data U, HttpServletResponse Response)
    throws Exception
      {
        UserDetail_Data UD = UserDetail_Factory.lookupByUserRefnum(U.getRefnum());
        if (UD.read(C) == false)
          {
            Response.sendError(HttpStatus.InternalServerError._Code, "Cannot load Person object based on User info");
            throw new ServletException("System error: unmatched Person and User");
          }
        return UD;
      }

    private boolean isUserLocked(User_Data U)
      {
        return U.getLocked() != null && ChronoUnit.MILLIS.between(ZonedDateTime.now(), U.getLocked()) > 0;
      }
  }
