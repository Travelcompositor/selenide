package com.codeborne.selenide.impl;

import com.codeborne.selenide.SelenideElement;
import com.codeborne.selenide.commands.Commands;
import com.codeborne.selenide.ex.InvalidStateException;
import com.codeborne.selenide.ex.UIAssertionError;
import com.codeborne.selenide.logevents.SelenideLog;
import com.codeborne.selenide.logevents.SelenideLogger;
import org.openqa.selenium.InvalidElementStateException;
import org.openqa.selenium.WebDriverException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Configuration.AssertionMode.SOFT;
import static com.codeborne.selenide.Configuration.*;
import static com.codeborne.selenide.Selenide.sleep;
import static com.codeborne.selenide.logevents.LogEvent.EventStatus.PASS;
import static java.lang.System.currentTimeMillis;
import static java.util.Arrays.asList;

class SelenideElementProxy implements InvocationHandler {
  private static final Set<String> methodsToSkipLogging = new HashSet<>(asList(
      "toWebElement",
      "toString"
  ));

  private static final Set<String> methodsForSoftAssertion = new HashSet<>(asList(
      "should",
      "shouldBe",
      "shouldHave",
      "shouldNot",
      "shouldNotHave",
      "shouldNotBe",
      "waitUntil",
      "waitWhile"
  ));

  private final WebElementSource webElementSource;
  
  protected SelenideElementProxy(WebElementSource webElementSource) {
    this.webElementSource = webElementSource;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object... args) throws Throwable {
    if (methodsToSkipLogging.contains(method.getName()))
      return Commands.collection.execute(proxy, webElementSource, method.getName(), args);

    long timeoutMs = getTimeoutMs(method, args);
    SelenideLog log = SelenideLogger.beginStep(webElementSource.getSearchCriteria(), method.getName(), args);
    try {
      Object result = dispatchAndRetry(timeoutMs, proxy, method, args);
      SelenideLogger.commitStep(log, PASS);
      return result;
    }
    catch (Error error) {
      SelenideLogger.commitStep(log, error);
      if (assertionMode == SOFT && methodsForSoftAssertion.contains(method.getName()))
        return proxy;
      else
        throw UIAssertionError.wrap(error, timeoutMs);
    }
    catch (RuntimeException error) {
      SelenideLogger.commitStep(log, error);
      throw error;
    }
  }

  protected Object dispatchAndRetry(long timeoutMs, Object proxy, Method method, Object[] args) throws Throwable {
    final long startTime = currentTimeMillis();
    Throwable lastError;
    do {
      try {
        if (SelenideElement.class.isAssignableFrom(method.getDeclaringClass())) {
          return Commands.collection.execute(proxy, webElementSource, method.getName(), args);
        }

        return method.invoke(webElementSource.getWebElement(), args);
      }
      catch (Throwable e) {
        if (Cleanup.of.isInvalidSelectorError(e)) {
          throw Cleanup.of.wrap(e);
        }
        lastError = e;
        sleep(pollingInterval);
      }
    }
    while (currentTimeMillis() - startTime <= timeoutMs);

    if (lastError instanceof UIAssertionError) {
      throw lastError;
    }
    else if (lastError instanceof InvalidElementStateException) {
      throw new InvalidStateException(lastError);
    }
    else if (lastError instanceof WebDriverException) {
      throw webElementSource.createElementNotFoundError(exist, lastError);
    }
    throw lastError;
  }

  private long getTimeoutMs(Method method, Object[] args) {
    return "waitUntil".equals(method.getName()) || "waitWhile".equals(method.getName()) ?
        (Long) args[args.length - 1] : timeout;
  }
}
