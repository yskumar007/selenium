// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.selenium.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.openqa.selenium.remote.DriverCommand.FIND_ELEMENT;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.openqa.selenium.By;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.StubElement;
import org.openqa.selenium.WebDriver;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@RunWith(JUnit4.class)
public class AugmenterTest extends BaseAugmenterTest {

  @Override
  public BaseAugmenter getAugmenter() {
    return new Augmenter();
  }

  @Test
  public void shouldAllowReflexiveCalls() {
    DesiredCapabilities caps = new DesiredCapabilities();
    caps.setCapability(CapabilityType.SUPPORTS_FINDING_BY_CSS, true);
    StubExecutor executor = new StubExecutor(caps);
    executor.expect(FIND_ELEMENT, ImmutableMap.of("using", "css selector", "value", "cheese"),
        new StubElement());

    WebDriver driver = new RemoteWebDriver(executor, caps);
    WebDriver returned = getAugmenter().augment(driver);

    returned.findElement(By.cssSelector("cheese"));
    // No exception is a Good Thing
  }

  @Test
  public void canUseTheAugmenterToInterceptConcreteMethodCalls() throws Exception {
    DesiredCapabilities caps = new DesiredCapabilities();
    caps.setJavascriptEnabled(true);
    StubExecutor stubExecutor = new StubExecutor(caps);
    stubExecutor.expect(DriverCommand.GET_TITLE, Maps.<String, Object>newHashMap(),
        "StubTitle");

    final WebDriver driver = new RemoteWebDriver(stubExecutor, caps);

    // Our AugmenterProvider needs to target the class that declares quit(),
    // otherwise the Augmenter won't apply the method interceptor.
    final Method quitMethod = driver.getClass().getMethod("quit");

    AugmenterProvider augmentation = new AugmenterProvider() {
      public Class<?> getDescribedInterface() {
        return quitMethod.getDeclaringClass();
      }

      public InterfaceImplementation getImplementation(Object value) {
        return new InterfaceImplementation() {
          public Object invoke(ExecuteMethod executeMethod, Object self,
              Method method, Object... args) {
            if (quitMethod.equals(method)) {
              return null;
            }

            try {
              return method.invoke(driver, args);
            } catch (IllegalAccessException e) {
              throw Throwables.propagate(e);
            } catch (InvocationTargetException e) {
              throw Throwables.propagate(e.getTargetException());
            }
          }
        };
      }
    };

    BaseAugmenter augmenter = getAugmenter();

    // Set the capability that triggers the augmentation.
    augmenter.addDriverAugmentation(CapabilityType.SUPPORTS_JAVASCRIPT, augmentation);

    WebDriver returned = augmenter.augment(driver);
    assertNotSame(driver, returned);
    assertEquals("StubTitle", returned.getTitle());

    returned.quit();   // Should not fail because it's intercepted.

    // Verify original is unmodified.
    boolean threw = false;
    try {
      driver.quit();
    } catch (AssertionError expected) {
      assertTrue(expected.getMessage().startsWith("Unexpected method invocation"));
      threw = true;
    }
    assertTrue("Did not throw", threw);
  }

  @Test
  public void shouldNotAugmentRemoteWebDriverWithoutExtraCapabilities() {
    Capabilities caps = new DesiredCapabilities();
    StubExecutor stubExecutor = new StubExecutor(caps);
    WebDriver driver = new RemoteWebDriver(stubExecutor, caps);

    WebDriver augmentedDriver = getAugmenter().augment(driver);

    assertThat(augmentedDriver, sameInstance(driver));
  }

  @Test
  public void shouldAugmentRemoteWebDriverWithExtraCapabilities() {
    DesiredCapabilities caps = new DesiredCapabilities();
    caps.setCapability(CapabilityType.SUPPORTS_FINDING_BY_CSS, true);
    StubExecutor stubExecutor = new StubExecutor(caps);
    WebDriver driver = new RemoteWebDriver(stubExecutor, caps);

    WebDriver augmentedDriver = getAugmenter().augment(driver);

    assertThat(augmentedDriver, not(sameInstance(driver)));
  }

  public static class RemoteWebDriverSubclass extends RemoteWebDriver {
    public RemoteWebDriverSubclass(CommandExecutor stubExecutor, Capabilities caps) {
      super(stubExecutor, caps);
    }
  }

  @Test
  public void shouldNotAugmentSubclassesOfRemoteWebDriver() {
    Capabilities caps = new DesiredCapabilities();
    StubExecutor stubExecutor = new StubExecutor(caps);
    WebDriver driver = new RemoteWebDriverSubclass(stubExecutor, caps);

    WebDriver augmentedDriver = getAugmenter().augment(driver);

    assertThat(augmentedDriver, sameInstance(driver));
  }
}
