/*
 * Copyright 2023 The Fury Authors
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

package io.fury.serializer;

import io.fury.Fury;
import io.fury.FuryTestBase;
import java.net.MalformedURLException;
import java.net.URL;
import org.testng.Assert;
import org.testng.annotations.Test;

public class URLSerializerTest extends FuryTestBase {

  @Test(dataProvider = "javaFury")
  public void testDefaultWrite(Fury fury) throws MalformedURLException {
    Assert.assertEquals(
        serDeCheckSerializer(fury, new URL("http://test"), "ReplaceResolve"),
        new URL("http://test"));
  }

  @Test
  public void testURLSerializer() throws MalformedURLException {
    Fury fury = Fury.builder().build();
    fury.registerSerializer(URL.class, URLSerializer.class);
    Assert.assertEquals(
        serDeCheckSerializer(fury, new URL("http://test"), "URLSerializer"),
        new URL("http://test"));
  }
}
