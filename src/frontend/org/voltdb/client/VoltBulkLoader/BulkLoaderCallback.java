/* This file is part of VoltDB.
 * Copyright (C) 2008-2017 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.client.VoltBulkLoader;

import org.voltdb.client.ClientResponse;

/**
 * BulkLoaderCallback is an interface describing the callback that
 * VoltBulkLoader will use when an individual insertRow() is processed.  Implementations
 * should perform actions based on the supplied client response. Error cases
 * typically occur if the object[] is the wrong size for the table, no
 * appropriate conversion for a given object can be found given corresponding
 * column data type, a constraint violation is found.
 */
public interface BulkLoaderCallback {

    /**
     * <p>Invoked by the VoltBulkLoader when a insertRow() is processed.</p>
     *
     * @param rowHandle parameter received in the insertRow().
     * @param array of row objects that were processed. In an error case, these are the rows that were unsuccessfully inserted.
     * @param response generated by VoltDB indicating the type of failure that occurred, or SUCCESS if successful.
     */
    public void callback(Object rowHandle, Object[] fieldList, ClientResponse response);

}
