package com.github.cloudyrock.mongock;

import com.github.cloudyrock.mongock.decorator.impl.MongoDataBaseDecoratorImpl;
import com.github.cloudyrock.mongock.decorator.util.MethodInvoker;
import com.github.cloudyrock.mongock.decorator.util.MethodInvokerImpl;
import com.github.cloudyrock.mongock.decorator.util.VoidSupplier;
import com.github.cloudyrock.mongock.test.proxy.ProxiesMongockTestResource;
import com.github.fakemongo.Fongo;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.junit.Before;
import org.junit.Test;
import org.mockito.internal.verification.Times;

import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import static java.util.Collections.singletonList;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @since 04/04/2018
 */
public class MongockLockCheckerIntegrationTest {

  private static final String LOCK_COLLECTION_NAME = "mongocklock";
  private static final Set<String> unInterceptedMethods =
      new HashSet<>(Arrays.asList("getCollection", "getCollectionFromString", "getDatabase", "toString"));
  private static final Set<String> proxyCreatordMethods =
      new HashSet<>(Arrays.asList("getCollection", "getCollectionFromString", "getDatabase"));
  private Mongock runner;
  private MongoDatabase mongoDatabase;
  private ChangeService changeService;
  private LockRepository lockRepository;
  private TimeUtils timeUtils;
  private LockChecker lockChecker;
  MethodInvoker methodInvoker;

  private ChangeEntryRepository changeEntryRepository;
  private TestMongockBuilder builder;

  @Before
  public void setUp() throws NoSuchMethodException, MongockException {
//    mongoDatabase = spy(new Fongo("testServer").getDatabase("mongocktest"));
    mongoDatabase = new Fongo("testServer").getDatabase("mongocktest");
    String name = mongoDatabase.getName();
    MongoClient mongoClient = mock(MongoClient.class);
    when(mongoClient.getDatabase(anyString())).thenReturn(mongoDatabase);

    builder = new TestMongockBuilder(mongoClient, "mongocktest", ProxiesMongockTestResource.class.getPackage().getName())
        .setEnabled(true)
        .setThrowExceptionIfCannotObtainLock(true);

    timeUtils = spy(new TimeUtils());
    changeService = spy(new ChangeService());
    lockRepository = spy(new LockRepository(LOCK_COLLECTION_NAME, mongoDatabase));

    lockChecker = spy(new LockChecker(lockRepository, timeUtils));
    methodInvoker = new MethodInvoker() {
      @Override
      public <T> T invoke(Supplier<T> supplier) {
        try {
          lockChecker.ensureLockDefault();
          return supplier.get();
        } catch (LockCheckException e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public void invoke(VoidSupplier supplier) {
        try {
          lockChecker.ensureLockDefault();
          supplier.execute();
        } catch (LockCheckException e) {
          throw new RuntimeException(e);
        }
      }
    };
    doReturn(singletonList(ProxiesMongockTestResource.class))
        .when(changeService).fetchChangeLogs();
    doReturn(singletonList(ProxiesMongockTestResource.class.getDeclaredMethod("testMongoDatabase", MongoDatabase.class)))
        .when(changeService).fetchChangeSets(ProxiesMongockTestResource.class);

    this.changeEntryRepository = mock(ChangeEntryRepository.class);
  }

  @Test
  public void shouldCallEnsureLock() throws Exception {
    when(changeEntryRepository.isNewChange(any(ChangeEntry.class))).thenReturn(true);
    MongoDatabase mongoDatabaseProxy = new MongoDataBaseDecoratorImpl(mongoDatabase, methodInvoker);

    runner = builder.build(changeEntryRepository, changeService, lockChecker, mongoDatabaseProxy);
    // when
    runner.execute();

    //then
    verify(lockChecker, new Times(1)).acquireLockDefault();
    verify(lockChecker, new Times(2)).ensureLockDefault();
  }

  @Test
  public void ensureLockShouldTryToRefreshLockIfExpiredButStillOwner() throws Exception {
    //given
    int tenMinutes = 10 * 60 * 1000;
    doReturn(new Date(System.currentTimeMillis() - tenMinutes))
        .doReturn(new Date(System.currentTimeMillis() + tenMinutes))
        .when(timeUtils).currentTime();
    when(changeEntryRepository.isNewChange(any(ChangeEntry.class))).thenReturn(true);
    MongoDatabase mongoDatabaseProxy = new MongoDataBaseDecoratorImpl(mongoDatabase, methodInvoker);//proxyFactory.createProxyFromOriginal(mongoDatabase);
    runner = builder.build(changeEntryRepository, changeService, lockChecker, mongoDatabaseProxy);

    // when
    runner.execute();

    //then
    verify(lockChecker, new Times(1)).acquireLockDefault();
    verify(lockChecker, new Times(2)).ensureLockDefault();
    verify(lockRepository, new Times(1)).insertUpdate(any(LockEntry.class));
    verify(lockRepository, new Times(1)).updateIfSameOwner(any(LockEntry.class));

  }

  @Test(expected = MongockException.class)
  public void ensureLockShouldThrowExceptionWhenLockIsStolenByAnotherProcess() throws Exception {
    //given
    doReturn(new Date(System.currentTimeMillis() + 5000))
        .when(timeUtils).currentTimePlusMillis(anyLong());
    methodInvoker = new MethodInvoker() {
      @Override
      public <T> T invoke(Supplier<T> supplier) {
        try {
          lockChecker.ensureLockDefault();
          replaceCurrentLockWithOtherOwner("anotherOwner");
          return supplier.get();
        } catch (LockCheckException e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public void invoke(VoidSupplier supplier) {
        try {
          lockChecker.ensureLockDefault();
          replaceCurrentLockWithOtherOwner("anotherOwner");
          supplier.execute();
        } catch (LockCheckException e) {
          throw new RuntimeException(e);
        }
      }
    };
    when(changeEntryRepository.isNewChange(any(ChangeEntry.class))).thenReturn(true);
    MongoDatabase mongoDatabaseProxy = new MongoDataBaseDecoratorImpl(mongoDatabase, methodInvoker);//proxyFactory.createProxyFromOriginal(mongoDatabase);
    runner = builder.build(changeEntryRepository, changeService, lockChecker, mongoDatabaseProxy);

    // when
    runner.execute();

    //then should throw MongockException
  }

  //Private helper method to replace the lock with a different owner
  private void replaceCurrentLockWithOtherOwner(String owner) {
    Document document = new Document();
    document.append("key", "DEFAULT_LOCK")
        .append("status", "LOCK_HELD")
        .append("owner", owner)
        .append("expiresAt", System.currentTimeMillis() + 10000);
    mongoDatabase.getCollection(LOCK_COLLECTION_NAME).updateMany(
        new Document("key", "DEFAULT_LOCK"),
        new Document().append("$set", document),
        new UpdateOptions().upsert(false));
  }

}


class TestMongockBuilder extends MongockBuilderBase<TestMongockBuilder, Mongock> {

  private ChangeService changeService;
  private MongoDatabase mongoDataBase;
  private ChangeEntryRepository changeEntryRepository;
  private LockChecker lockChecker;

  public TestMongockBuilder(MongoClient mongoClient, String databaseName, String changeLogsScanPackage) {
    super(mongoClient, databaseName, changeLogsScanPackage);
  }

  @Override
  protected TestMongockBuilder returnInstance() {
    return this;
  }

  Mongock build(ChangeEntryRepository changeEntryRepository,
                ChangeService changeService,
                LockChecker lockChecker,
                MongoDatabase mongoDatabase) {
    this.changeEntryRepository = changeEntryRepository;
    this.changeService = changeService;
    this.lockChecker = lockChecker;
    this.mongoDataBase = mongoDatabase;
    return createBuild();
  }

  @Override
  Mongock createBuild() {
//    changeService.setChangeLogsBasePackage(changeLogsScanPackage);
    Mongock mongock = new Mongock(changeEntryRepository, mongoClient, changeService, lockChecker);
    mongock.setChangelogMongoDatabase(mongoDataBase);
    mongock.setEnabled(enabled);
    mongock.setThrowExceptionIfCannotObtainLock(throwExceptionIfCannotObtainLock);
    return mongock;
  }
}
