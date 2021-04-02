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

package wanda.servlets;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import javax.servlet.annotation.WebServlet;
import wanda.data.User_Data;
import wanda.data.User_Factory;

import tilda.db.Connection;
import wanda.web.RequestUtil;
import wanda.web.ResponseUtil;
import wanda.web.SimpleServlet;

@WebServlet("/svc/user/forgotPswd")
public class ForgotPassword extends SimpleServlet
  {

    private static final long serialVersionUID = 988554219257979935L;

    public ForgotPassword()
      {
        super(false, false);
      }

    @Override
    protected void justDo(RequestUtil req, ResponseUtil Res, Connection C, User_Data U)
    throws Exception
      {
        String email = req.getParamString("email", true);
        req.throwIfErrors();

        email = email.toLowerCase();
        User_Data u = User_Factory.lookupByEmail(email);
        if (u.read(C) && (u.isLockedNull() || ChronoUnit.MILLIS.between(ZonedDateTime.now(), u.getLocked()) < 1))
          {
            u.sendForgotPswdEmail(C);
          }
        Res.success();
      }
  }
