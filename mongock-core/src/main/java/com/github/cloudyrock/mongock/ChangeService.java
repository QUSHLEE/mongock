package com.github.cloudyrock.mongock;

import com.google.common.reflect.ClassPath;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static com.github.cloudyrock.mongock.StringUtils.hasText;
import static java.util.Arrays.asList;

/**
 * Utilities to deal with reflections and annotations
 *
 * @since 27/07/2014
 */
class ChangeService {
  private static final String DEFAULT_PROFILE = "default";

  private String changeLogsBasePackage;

  ChangeService() {
  }

  private static boolean isProfileAnnotationPresent() {
    try {
      Class.forName("org.springframework.context.annotation.Profile");
      return true;
    } catch (Exception ex) {
      return false;
    }
  }

  /**
   * <p>Indicates the package to scan changeLogs</p>
   *
   * @param changeLogsBasePackage path of the package
   */
  //Implementation note: This has been added, replacing constructor, to be able to inject this service as dependency
  void setChangeLogsBasePackage(String changeLogsBasePackage) {
    this.changeLogsBasePackage = changeLogsBasePackage;
  }


  @SuppressWarnings("unchecked")
  List<Class<?>> fetchChangeLogs() {
    try {
      ClassPath classPath = ClassPath.from(Thread.currentThread().getContextClassLoader());
      return classPath.getTopLevelClassesRecursive(changeLogsBasePackage).stream()
          .filter(classInfo -> classInfo.load().isAnnotationPresent(ChangeLog.class))
          .map(ClassPath.ClassInfo::load)
          .sorted(new ChangeLogComparator())
          .collect(Collectors.toList());
    } catch (IOException e) {
      throw new MongockException("Could not read ChangeLog classes from package " + changeLogsBasePackage, e);
    }
  }

  @SuppressWarnings("unchecked")
  List<Method> fetchChangeSets(final Class<?> type) throws MongockException {
    final List<Method> changeSets = filterChangeSetAnnotation(asList(type.getDeclaredMethods()));

    Collections.sort(changeSets, new ChangeSetComparator());

    return changeSets;
  }

  boolean isRunAlwaysChangeSet(Method changesetMethod) {
    if (changesetMethod.isAnnotationPresent(ChangeSet.class)) {
      ChangeSet annotation = changesetMethod.getAnnotation(ChangeSet.class);
      return annotation.runAlways();
    } else {
      return false;
    }
  }

  ChangeEntry createChangeEntry(Method changesetMethod) {
    if (changesetMethod.isAnnotationPresent(ChangeSet.class)) {
      ChangeSet annotation = changesetMethod.getAnnotation(ChangeSet.class);

      return new ChangeEntry(
          annotation.id(),
          annotation.author(),
          new Date(),
          changesetMethod.getDeclaringClass().getName(),
          changesetMethod.getName());
    } else {
      return null;
    }
  }

  /**
   * <p>It creates an instance from a given Class.</p>
   *
   * @param changelogClass class to create the instance from
   * @param <T>            Class parameter
   * @return an instance of the given class
   * @throws NoSuchMethodException     If reflection fails
   * @throws InvocationTargetException If reflection fails
   * @throws InstantiationException    If reflection fails
   */
  //Implementation note: It has been added as a more flexible way to get the changeLog objects and make easier testing.
  <T> T createInstance(Class<T> changelogClass) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException {
    return changelogClass.getConstructor().newInstance();
  }


  private List<Method> filterChangeSetAnnotation(List<Method> allMethods) throws MongockException {
    final Set<String> changeSetIds = new HashSet<>();
    final List<Method> changesetMethods = new ArrayList<>();
    for (final Method method : allMethods) {
      if (method.isAnnotationPresent(ChangeSet.class)) {
        String id = method.getAnnotation(ChangeSet.class).id();
        if (changeSetIds.contains(id)) {
          throw new MongockException(String.format("Duplicated changeset id found: '%s'", id));
        }
        changeSetIds.add(id);
        changesetMethods.add(method);
      }
    }
    return changesetMethods;
  }

  private static class ChangeLogComparator implements Comparator<Class<?>>, Serializable {
    private static final long serialVersionUID = -358162121872177974L;

    @Override
    public int compare(Class<?> o1, Class<?> o2) {
      ChangeLog c1 = o1.getAnnotation(ChangeLog.class);
      ChangeLog c2 = o2.getAnnotation(ChangeLog.class);

      String val1 = !(hasText(c1.order())) ? o1.getCanonicalName() : c1.order();
      String val2 = !(hasText(c2.order())) ? o2.getCanonicalName() : c2.order();

      if (val1 == null && val2 == null) {
        return 0;
      } else if (val1 == null) {
        return -1;
      } else if (val2 == null) {
        return 1;
      }

      return val1.compareTo(val2);
    }
  }

  private static class ChangeSetComparator implements Comparator<Method>, Serializable {
    private static final long serialVersionUID = -854690868262484102L;

    @Override
    public int compare(Method o1, Method o2) {
      ChangeSet c1 = o1.getAnnotation(ChangeSet.class);
      ChangeSet c2 = o2.getAnnotation(ChangeSet.class);
      return c1.order().compareTo(c2.order());
    }
  }

}
