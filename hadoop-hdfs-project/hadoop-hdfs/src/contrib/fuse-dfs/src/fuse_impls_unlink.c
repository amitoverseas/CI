/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "fuse_dfs.h"
#include "fuse_impls.h"
#include "fuse_connect.h"
#include "fuse_trash.h"
extern const char *const TrashPrefixDir;

int dfs_unlink(const char *path)
{
  TRACE1("unlink", path)

  int ret = 0;
  dfs_context *dfs = (dfs_context*)fuse_get_context()->private_data;

  assert(path);
  assert(dfs);
  assert('/' == *path);

  if (is_protected(path)) {
    ERROR("Trying to delete protected directory %s", path);
    return -EACCES;
  }

  if (dfs->read_only) {
    ERROR("HDFS configured read-only, cannot create directory %s", path);
    return -EACCES;
  }

  hdfsFS userFS = doConnectAsUser(dfs->nn_hostname, dfs->nn_port);
  if (userFS == NULL) {
    ERROR("Could not connect");
    return -EIO;
  }

  if (hdfsDeleteWithTrash(userFS, path, dfs->usetrash)) {
    ERROR("Could not delete file %s", path);
    ret = (errno > 0) ? -errno : -EIO;
    goto cleanup;
  }

cleanup:
  if (doDisconnect(userFS)) {
    ret = -EIO;
  }
  return ret;

}
