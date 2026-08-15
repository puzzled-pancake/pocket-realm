/**************************************************************************
 *
 * Copyright (C) 2015 Red Hat Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 *
 **************************************************************************/
#include <stdio.h>
#include <signal.h>
#include <stdbool.h>
#include <unistd.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <sys/un.h>
#include <fcntl.h>
#include <getopt.h>
#include <string.h>
#include <pthread.h>

#include "util/u_memory.h"
#include "virgl_server.h"
#include "virgl_server_protocol.h"

static pthread_mutex_t active_client_mutex = PTHREAD_MUTEX_INITIALIZER;
static struct virgl_client *active_client;

#define VIRGL_MAX_COMMAND_DWORDS (4U * 1024U * 1024U)

static bool virgl_server_command_length_valid(uint32_t command, uint32_t length)
{
   switch (command) {
      case VCMD_CREATE_RENDERER:
      case VCMD_GET_CAPS:
         return length == 0;
      case VCMD_RESOURCE_CREATE:
         return length == 11;
      case VCMD_RESOURCE_DESTROY:
         return length == 1;
      case VCMD_TRANSFER_GET:
      case VCMD_TRANSFER_PUT:
         return length == 10;
      case VCMD_SUBMIT_CMD:
         return length > 0 && length <= VIRGL_MAX_COMMAND_DWORDS;
      case VCMD_RESOURCE_BUSY_WAIT:
      case VCMD_FLUSH_FRONTBUFFER:
         return length == 2;
      default:
         return false;
   }
}

static struct virgl_client *virgl_server_handle_new_connection(int fd, uint64_t surface_generation)
{
   struct virgl_client *client = calloc(1, sizeof(struct virgl_client));
   if (!client)
      return NULL;
   client->fd = fd;
   client->initialized = false;
   client->renderer = NULL;
   pthread_mutex_lock(&active_client_mutex);
   if (active_client) {
      pthread_mutex_unlock(&active_client_mutex);
      free(client);
      return NULL;
   }
   client->renderer = calloc(1, sizeof(struct virgl_server_renderer));
   if (!client->renderer) {
      pthread_mutex_unlock(&active_client_mutex);
      free(client);
      return NULL;
   }
   client->renderer->egl_display = EGL_NO_DISPLAY;
   client->renderer->egl_ctx = EGL_NO_CONTEXT;
   client->renderer->surface_generation = surface_generation;
   active_client = client;
   pthread_mutex_unlock(&active_client_mutex);
   return client;
}

static void virgl_server_destroy_client(struct virgl_client **client)
{
   if (!client || !*client)
      return;
   virgl_server_destroy_renderer(*client);
   pthread_mutex_lock(&active_client_mutex);
   if (active_client == *client)
      active_client = NULL;
   pthread_mutex_unlock(&active_client_mutex);
   free(*client);
   *client = NULL;
}

/* Returns bit 0 when initialized, bit 1 after a successful caps response. */
static int virgl_server_handle_request(struct virgl_client *client)
{
   int ret = -EINVAL;
   uint32_t header[2];

   ret = virgl_block_read(client->fd, &header, sizeof(header));
   if (ret < 0 || (size_t)ret < sizeof(header)) {
      return -1;
   }

   if (!virgl_server_command_length_valid(header[1], header[0])) {
      return -1;
   }

   if (!client->initialized) {
      if (header[1] != VCMD_CREATE_RENDERER) {
         return -1;
      }

      ret = virgl_server_create_renderer(client, header[0]);
      if (ret < 0) {
         return -1;
      }
      client->initialized = true;
      return 1;
   }

   vrend_renderer_check_fences(client);
   
   switch (header[1]) {
      case VCMD_GET_CAPS:
         ret = virgl_server_send_caps(client, header[0]);
         break;
      case VCMD_RESOURCE_CREATE:
         ret = virgl_server_resource_create(client, header[0]);
         break;
      case VCMD_RESOURCE_DESTROY:
         ret = virgl_server_resource_destroy(client, header[0]);
         break;
      case VCMD_TRANSFER_GET:
         ret = virgl_server_transfer_get(client, header[0]);
         break;
      case VCMD_TRANSFER_PUT:
         ret = virgl_server_transfer_put(client, header[0]);
         break;
      case VCMD_SUBMIT_CMD:
         ret = virgl_server_submit_cmd(client, header[0]);
         break;
      case VCMD_RESOURCE_BUSY_WAIT:
         ret = virgl_server_resource_busy_wait(client, header[0]);
         break;
      case VCMD_FLUSH_FRONTBUFFER:
         ret = virgl_server_flush_frontbuffer(client, header[0]);
         break;
   }
   
   if (ret < 0)
      return -1;
   return 1 | (header[1] == VCMD_GET_CAPS ? 2 : 0);
}

JNIEXPORT jlong JNICALL
Java_com_winlator_xenvironment_components_VirGLRendererComponent_handleNewConnection(JNIEnv *env, jobject obj,
                                                                                      jint fd, jlong surface_generation) {
   jclass cls = (*env)->GetObjectClass(env, obj);
   if (surface_generation <= 0)
      return 0;
   struct virgl_client *client = virgl_server_handle_new_connection(
      fd, (uint64_t)surface_generation);
   if (!client)
      return 0;
   client->flush_frontbuffer = (*env)->GetMethodID(env, cls, "flushFrontbuffer", "(II)V");
   if (!client->flush_frontbuffer) {
      if ((*env)->ExceptionCheck(env))
         (*env)->ExceptionClear(env);
      virgl_server_destroy_client(&client);
      return 0;
   }
   return (jlong)client;
}

JNIEXPORT jint JNICALL
Java_com_winlator_xenvironment_components_VirGLRendererComponent_handleRequest(JNIEnv *env, jobject obj, jlong clientPtr) {
   struct virgl_client *client = (struct virgl_client*)clientPtr;
   if (!client)
      return -1;
   client->request_env = env;
   client->request_obj = obj;
   int result = virgl_server_handle_request(client);
   client->request_env = NULL;
   client->request_obj = NULL;
   return result;
}

JNIEXPORT void JNICALL
Java_com_winlator_xenvironment_components_VirGLRendererComponent_destroyClient(JNIEnv *env, jobject obj, jlong clientPtr) {
   struct virgl_client *client = (struct virgl_client*)clientPtr;
   virgl_server_destroy_client(&client);
}

