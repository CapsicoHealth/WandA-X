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

package wanda.data.importers.people;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.annotations.SerializedName;

import tilda.Importer;
import tilda.db.Connection;

public class RootImporter implements Importer
  {
    /*@formatter:off*/
    @SerializedName("people") public List<User>      _Users = new ArrayList<User>();
    /*@formatter:on*/

    @Override
    public int process(Connection C)
    throws Exception
      {
        int Count = 0;

        for (User obj : _Users)
          {
            ++Count;
            obj.write(C);
          }

        return Count;
      }
    /*@formatter:on*/
  }
