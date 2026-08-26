package com.openwrtmgr.app.core.database;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class RouterProfileDao_Impl implements RouterProfileDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<RouterProfileEntity> __insertionAdapterOfRouterProfileEntity;

  private final EntityDeletionOrUpdateAdapter<RouterProfileEntity> __deletionAdapterOfRouterProfileEntity;

  private final EntityDeletionOrUpdateAdapter<RouterProfileEntity> __updateAdapterOfRouterProfileEntity;

  public RouterProfileDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfRouterProfileEntity = new EntityInsertionAdapter<RouterProfileEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `router_profiles` (`id`,`name`,`host`,`port`,`useHttps`,`username`,`sshPort`,`lastConnectedEpochMillis`) VALUES (nullif(?, 0),?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final RouterProfileEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getName());
        statement.bindString(3, entity.getHost());
        statement.bindLong(4, entity.getPort());
        final int _tmp = entity.getUseHttps() ? 1 : 0;
        statement.bindLong(5, _tmp);
        statement.bindString(6, entity.getUsername());
        statement.bindLong(7, entity.getSshPort());
        if (entity.getLastConnectedEpochMillis() == null) {
          statement.bindNull(8);
        } else {
          statement.bindLong(8, entity.getLastConnectedEpochMillis());
        }
      }
    };
    this.__deletionAdapterOfRouterProfileEntity = new EntityDeletionOrUpdateAdapter<RouterProfileEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "DELETE FROM `router_profiles` WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final RouterProfileEntity entity) {
        statement.bindLong(1, entity.getId());
      }
    };
    this.__updateAdapterOfRouterProfileEntity = new EntityDeletionOrUpdateAdapter<RouterProfileEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `router_profiles` SET `id` = ?,`name` = ?,`host` = ?,`port` = ?,`useHttps` = ?,`username` = ?,`sshPort` = ?,`lastConnectedEpochMillis` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final RouterProfileEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getName());
        statement.bindString(3, entity.getHost());
        statement.bindLong(4, entity.getPort());
        final int _tmp = entity.getUseHttps() ? 1 : 0;
        statement.bindLong(5, _tmp);
        statement.bindString(6, entity.getUsername());
        statement.bindLong(7, entity.getSshPort());
        if (entity.getLastConnectedEpochMillis() == null) {
          statement.bindNull(8);
        } else {
          statement.bindLong(8, entity.getLastConnectedEpochMillis());
        }
        statement.bindLong(9, entity.getId());
      }
    };
  }

  @Override
  public Object upsert(final RouterProfileEntity profile,
      final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfRouterProfileEntity.insertAndReturnId(profile);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object delete(final RouterProfileEntity profile,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __deletionAdapterOfRouterProfileEntity.handle(profile);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object update(final RouterProfileEntity profile,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __updateAdapterOfRouterProfileEntity.handle(profile);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<RouterProfileEntity>> observeAll() {
    final String _sql = "SELECT * FROM router_profiles ORDER BY lastConnectedEpochMillis DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"router_profiles"}, new Callable<List<RouterProfileEntity>>() {
      @Override
      @NonNull
      public List<RouterProfileEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfHost = CursorUtil.getColumnIndexOrThrow(_cursor, "host");
          final int _cursorIndexOfPort = CursorUtil.getColumnIndexOrThrow(_cursor, "port");
          final int _cursorIndexOfUseHttps = CursorUtil.getColumnIndexOrThrow(_cursor, "useHttps");
          final int _cursorIndexOfUsername = CursorUtil.getColumnIndexOrThrow(_cursor, "username");
          final int _cursorIndexOfSshPort = CursorUtil.getColumnIndexOrThrow(_cursor, "sshPort");
          final int _cursorIndexOfLastConnectedEpochMillis = CursorUtil.getColumnIndexOrThrow(_cursor, "lastConnectedEpochMillis");
          final List<RouterProfileEntity> _result = new ArrayList<RouterProfileEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final RouterProfileEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpHost;
            _tmpHost = _cursor.getString(_cursorIndexOfHost);
            final int _tmpPort;
            _tmpPort = _cursor.getInt(_cursorIndexOfPort);
            final boolean _tmpUseHttps;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfUseHttps);
            _tmpUseHttps = _tmp != 0;
            final String _tmpUsername;
            _tmpUsername = _cursor.getString(_cursorIndexOfUsername);
            final int _tmpSshPort;
            _tmpSshPort = _cursor.getInt(_cursorIndexOfSshPort);
            final Long _tmpLastConnectedEpochMillis;
            if (_cursor.isNull(_cursorIndexOfLastConnectedEpochMillis)) {
              _tmpLastConnectedEpochMillis = null;
            } else {
              _tmpLastConnectedEpochMillis = _cursor.getLong(_cursorIndexOfLastConnectedEpochMillis);
            }
            _item = new RouterProfileEntity(_tmpId,_tmpName,_tmpHost,_tmpPort,_tmpUseHttps,_tmpUsername,_tmpSshPort,_tmpLastConnectedEpochMillis);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object getById(final long id,
      final Continuation<? super RouterProfileEntity> $completion) {
    final String _sql = "SELECT * FROM router_profiles WHERE id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, id);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<RouterProfileEntity>() {
      @Override
      @Nullable
      public RouterProfileEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfHost = CursorUtil.getColumnIndexOrThrow(_cursor, "host");
          final int _cursorIndexOfPort = CursorUtil.getColumnIndexOrThrow(_cursor, "port");
          final int _cursorIndexOfUseHttps = CursorUtil.getColumnIndexOrThrow(_cursor, "useHttps");
          final int _cursorIndexOfUsername = CursorUtil.getColumnIndexOrThrow(_cursor, "username");
          final int _cursorIndexOfSshPort = CursorUtil.getColumnIndexOrThrow(_cursor, "sshPort");
          final int _cursorIndexOfLastConnectedEpochMillis = CursorUtil.getColumnIndexOrThrow(_cursor, "lastConnectedEpochMillis");
          final RouterProfileEntity _result;
          if (_cursor.moveToFirst()) {
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpHost;
            _tmpHost = _cursor.getString(_cursorIndexOfHost);
            final int _tmpPort;
            _tmpPort = _cursor.getInt(_cursorIndexOfPort);
            final boolean _tmpUseHttps;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfUseHttps);
            _tmpUseHttps = _tmp != 0;
            final String _tmpUsername;
            _tmpUsername = _cursor.getString(_cursorIndexOfUsername);
            final int _tmpSshPort;
            _tmpSshPort = _cursor.getInt(_cursorIndexOfSshPort);
            final Long _tmpLastConnectedEpochMillis;
            if (_cursor.isNull(_cursorIndexOfLastConnectedEpochMillis)) {
              _tmpLastConnectedEpochMillis = null;
            } else {
              _tmpLastConnectedEpochMillis = _cursor.getLong(_cursorIndexOfLastConnectedEpochMillis);
            }
            _result = new RouterProfileEntity(_tmpId,_tmpName,_tmpHost,_tmpPort,_tmpUseHttps,_tmpUsername,_tmpSshPort,_tmpLastConnectedEpochMillis);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
