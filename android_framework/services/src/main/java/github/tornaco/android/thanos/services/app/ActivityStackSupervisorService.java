package github.tornaco.android.thanos.services.app;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import github.tornaco.android.thanos.BuildProp;
import github.tornaco.android.thanos.core.T;
import github.tornaco.android.thanos.core.annotation.GuardedBy;
import github.tornaco.android.thanos.core.annotation.Logging;
import github.tornaco.android.thanos.core.app.activity.ActivityStackSupervisor;
import github.tornaco.android.thanos.core.app.activity.IActivityStackSupervisor;
import github.tornaco.android.thanos.core.app.activity.IVerifyCallback;
import github.tornaco.android.thanos.core.app.activity.VerifyResult;
import github.tornaco.android.thanos.core.app.event.ThanosEvent;
import github.tornaco.android.thanos.core.persist.RepoFactory;
import github.tornaco.android.thanos.core.persist.StringMapRepo;
import github.tornaco.android.thanos.core.persist.i.SetRepo;
import github.tornaco.android.thanos.core.pm.PackageManager;
import github.tornaco.android.thanos.core.pref.IPrefChangeListener;
import github.tornaco.android.thanos.core.util.Noop;
import github.tornaco.android.thanos.core.util.PkgUtils;
import github.tornaco.android.thanos.core.util.Timber;
import github.tornaco.android.thanos.services.S;
import github.tornaco.android.thanos.services.ThanosSchedulers;
import github.tornaco.android.thanos.services.ThanoxSystemService;
import github.tornaco.android.thanos.services.perf.PreferenceManagerService;
import github.tornaco.java.common.util.ObjectsUtils;
import io.reactivex.Completable;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

@SuppressWarnings("FieldCanBeLocal")
public class ActivityStackSupervisorService extends ThanoxSystemService implements IActivityStackSupervisor {

    private static final AtomicInteger S_REQ = new AtomicInteger(0);

    private final Set<String> verifiedPackages = new HashSet<>();
    @SuppressLint("UseSparseArrays")
    @GuardedBy("ConcurrentHashMap")
    private final Map<Integer, VerifyRecord> verifyRecords = new ConcurrentHashMap<>();

    private SetRepo<String> lockingApps;

    private boolean lockerEnabled;
    private boolean lockerWorkaround;
    private boolean fingerPrintEnabled;
    private int lockerMethod = ActivityStackSupervisor.LockerMethod.NONE;

    private StringMapRepo componentReplacementRepo;

    private final AtomicReference<String> currentPresentPkgName = new AtomicReference<>(PackageManager.packageNameOfAndroid());

    public ActivityStackSupervisorService(S s) {
        super(s);
    }

    @Override
    public void onStart(Context context) {
        super.onStart(context);
        this.lockingApps = RepoFactory.get().getOrCreateStringSetRepo(T.appLockRepoFile().getPath());
        this.componentReplacementRepo = RepoFactory.get().getOrCreateStringMapRepo(T.componentReplacementRepoFile().getPath());
    }

    @Override
    public void systemReady() {
        super.systemReady();
        initPrefs();
    }

    private void initPrefs() {
        readPrefs();
        listenToPrefs();
    }

    private void readPrefs() {
        PreferenceManagerService preferenceManagerService = s.getPreferenceManagerService();
        this.lockerEnabled = preferenceManagerService.getBoolean(
                T.Settings.PREF_APP_LOCK_ENABLED.getKey(),
                T.Settings.PREF_APP_LOCK_ENABLED.getDefaultValue());
        this.fingerPrintEnabled = preferenceManagerService.getBoolean(
                T.Settings.PREF_APP_LOCK_FP_ENABLED.getKey(),
                T.Settings.PREF_APP_LOCK_FP_ENABLED.getDefaultValue());
        this.lockerWorkaround = preferenceManagerService.getBoolean(
                T.Settings.PREF_APP_LOCK_WORKAROUND_ENABLED.getKey(),
                T.Settings.PREF_APP_LOCK_WORKAROUND_ENABLED.getDefaultValue());
        this.lockerMethod = preferenceManagerService.getInt(
                T.Settings.PREF_APP_LOCK_METHOD.getKey(),
                T.Settings.PREF_APP_LOCK_METHOD.getDefaultValue());
    }

    private void listenToPrefs() {
        PreferenceManagerService preferenceManagerService = s.getPreferenceManagerService();
        preferenceManagerService.registerSettingsChangeListener(new IPrefChangeListener.Stub() {
            @Override
            public void onPrefChanged(String key) {
                if (ObjectsUtils.equals(T.Settings.PREF_APP_LOCK_ENABLED.getKey(), key)
                        || ObjectsUtils.equals(T.Settings.PREF_APP_LOCK_METHOD.getKey(), key)
                        || ObjectsUtils.equals(T.Settings.PREF_APP_LOCK_FP_ENABLED.getKey(), key)
                        || ObjectsUtils.equals(T.Settings.PREF_APP_LOCK_WORKAROUND_ENABLED.getKey(), key)) {
                    Timber.i("Pref changed, reload.");
                    readPrefs();
                }
            }
        });
    }

    @Override
    public boolean checkActivity(ComponentName componentName) {
        return true;
    }

    @Override
    public Intent replaceActivityStartingIntent(Intent intent) {
        ComponentName cName = intent.getComponent();
        if (cName == null) {
            return intent;
        }

        String cString = cName.flattenToString();
        boolean hasReplacement = componentReplacementRepo.hasNoneNullValue(cString);
        if (!hasReplacement) {
            return intent;
        }
        String replacement = componentReplacementRepo.get(cString);
        ComponentName newCName = ComponentName.unflattenFromString(replacement);
        if (newCName == null) {
            return intent;
        }
        Timber.d("Replace component from: %s; to: %s", cName, newCName);

        return intent.setComponent(newCName);
    }

    @Override
    public boolean shouldVerifyActivityStarting(ComponentName componentName, String pkg, String source) {
        Timber.v("shouldVerify? %s %s", componentName, source);
        return isAppLockEnabled()
                && !verifiedPackages.contains(pkg)
                && !isLockVerifyActivity(componentName)
                && isPackageLocked(pkg)
                && isSystemUser()
                && PkgUtils.isPkgInstalled(getContext(), BuildProp.THANOS_APP_PKG_NAME);
    }

    @Override
    public void verifyActivityStarting(Bundle options,
                                       String pkg,
                                       ComponentName componentName,
                                       int uid,
                                       int pid,
                                       IVerifyCallback callback) {
        dumpCallingUserId("verifyActivityStarting");
        VerifyRecord record = VerifyRecord.builder()
                .pid(pid)
                .uid(uid)
                .pkg(pkg)
                .requestCode(allocateRequestCode())
                .verifyCallback(callback)
                .componentName(componentName)
                .build();
        Intent intent = new Intent(T.Actions.ACTION_LOCKER_VERIFY_ACTION);
        intent.putExtra(T.Actions.ACTION_LOCKER_VERIFY_EXTRA_PACKAGE, pkg);
        intent.putExtra(T.Actions.ACTION_LOCKER_VERIFY_EXTRA_REQUEST_CODE, record.requestCode);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        verifyRecords.put(record.requestCode, record);
        try {
            // Not all roms has UserHandle.
            getContext().startActivityAsUser(intent, options, UserHandle.of(UserHandle.getCallingUserId()));
        } catch (Throwable e) {
            Timber.w("Fail startActivityAsUser %s", Log.getStackTraceString(e));
            getContext().startActivity(intent, options);
        }
    }

    @Override
    public void reportActivityLaunching(Intent intent, String reason) {
        Completable.fromRunnable(() -> reportActivityLaunchingInternal(intent, reason))
                .subscribeOn(ThanosSchedulers.serverThread())
                .subscribe();
    }

    private void reportActivityLaunchingInternal(Intent intent, String reason) {
        Timber.d("reportActivityLaunchingInternal: %s %s", intent, reason);

        String pkg = PkgUtils.packageNameOf(intent);
        String last = currentPresentPkgName.get();
        boolean changed = !ObjectsUtils.equals(last, pkg);
        currentPresentPkgName.set(pkg);

        if (changed) {
            onFrontPackageChangedInternal(last, pkg);
        }
    }

    @Override
    public String getCurrentFrontApp() {
        return currentPresentPkgName.get();
    }

    @Override
    public void setAppLockEnabled(boolean enabled) {
        lockerEnabled = enabled;
        PreferenceManagerService preferenceManagerService = s.getPreferenceManagerService();
        preferenceManagerService.putBoolean(T.Settings.PREF_APP_LOCK_ENABLED.getKey(), enabled);
    }

    @Override
    public boolean isAppLockEnabled() {
        return lockerEnabled;
    }

    @Override
    public boolean isPackageLocked(String pkg) {
        return lockingApps.has(pkg);
    }

    @Override
    public void setPackageLocked(String pkg, boolean locked) {
        if (locked) {
            lockingApps.add(pkg);
        } else {
            lockingApps.remove(pkg);
        }
    }

    @Override
    public void setVerifyResult(int request, int result, int reason) {
        if (verifyRecords.containsKey(request)) {
            VerifyRecord record = verifyRecords.remove(request);
            if (record == null) {
                Timber.e("Can not find record for request code :%s", request);
                return;
            }
            if (result == VerifyResult.ALLOW) {
                verifiedPackages.add(record.pkg);
            }
            notifyVerifyCallback(record, result, reason);
            Timber.v("setVerifyResult %s %s %s", request, result, reason);
        } else {
            Timber.e("No such request %s", request);
        }
    }

    @Override
    public void setLockerMethod(int method) {
        lockerMethod = method;
        PreferenceManagerService preferenceManagerService = s.getPreferenceManagerService();
        preferenceManagerService.putInt(T.Settings.PREF_APP_LOCK_METHOD.getKey(), method);
    }

    @Override
    public int getLockerMethod() {
        return lockerMethod;
    }

    @Override
    public void setLockerKey(int method, String key) {
        PreferenceManagerService preferenceManagerService = s.getPreferenceManagerService();
        preferenceManagerService.putString(T.Settings.PREF_APP_LOCK_KEY_PREFIX_.getKey() + method, key);
    }

    @Override
    public boolean isLockerKeyValid(int method, String key) {
        PreferenceManagerService preferenceManagerService = s.getPreferenceManagerService();
        String set = preferenceManagerService.getString(T.Settings.PREF_APP_LOCK_KEY_PREFIX_.getKey() + method, null);
        return key != null && ObjectsUtils.equals(key, set);
    }

    @Override
    public boolean isLockerKeySet(int method) {
        PreferenceManagerService preferenceManagerService = s.getPreferenceManagerService();
        String set = preferenceManagerService.getString(T.Settings.PREF_APP_LOCK_KEY_PREFIX_.getKey() + method, null);
        return !TextUtils.isEmpty(set);
    }

    @Override
    public boolean isFingerPrintEnabled() {
        return fingerPrintEnabled;
    }

    @Override
    public void setFingerPrintEnabled(boolean enable) {
        fingerPrintEnabled = enable;
        PreferenceManagerService preferenceManagerService = s.getPreferenceManagerService();
        preferenceManagerService.putBoolean(T.Settings.PREF_APP_LOCK_FP_ENABLED.getKey(), enable);
    }

    @Override
    public void addComponentReplacement(ComponentName from, ComponentName to) {
        enforceCallingPermissions();
        componentReplacementRepo.put(from.flattenToString(), to.flattenToString());
    }

    @Override
    public void removeComponentReplacement(ComponentName from) {
        enforceCallingPermissions();
        componentReplacementRepo.remove(from.flattenToString());
    }

    @Override
    public IBinder asBinder() {
        return Noop.notSupported();
    }

    private void notifyVerifyCallback(VerifyRecord record, int result, int reason) {
        long delay = lockerWorkaround ? T.Settings.PREF_APP_LOCK_WORKAROUND_DELAY.getDefaultValue() : 0;
        Timber.v("notifyVerifyCallback with delay %s", delay);
        Completable.fromAction(() -> record.verifyCallback.onVerifyResult(result, reason))
                .delay(Math.max(0, delay), TimeUnit.MILLISECONDS)
                .subscribeOn(ThanosSchedulers.serverThread())
                .subscribe();

    }

    private boolean isLockVerifyActivity(ComponentName name) {
        return name != null
                && BuildProp.ACTIVITY_APP_LOCK_VERIFIER
                .equals(name.getClassName());
    }

    private static int allocateRequestCode() {
        return S_REQ.getAndIncrement();
    }


    @Logging
    private void onRequestRuntimePermissions(String[] permissions) {
        Timber.d("onRequestRuntimePermissions: %s", Arrays.toString(permissions));
    }

    private void onFrontPackageChangedInternal(String from, String to) {
        Timber.d("onFrontPackageChangedInternal: %s %s", from, to);
        // Broadcast.
        Intent changedIntent = new Intent(T.Actions.ACTION_FRONT_PKG_CHANGED);
        changedIntent.putExtra(T.Actions.ACTION_FRONT_PKG_CHANGED_EXTRA_PACKAGE_TO, to);
        changedIntent.putExtra(T.Actions.ACTION_FRONT_PKG_CHANGED_EXTRA_PACKAGE_FROM, from);
        ThanosEvent event = new ThanosEvent(changedIntent);
        EventBus.getInstance().publishEventToSubscribersAsync(event);
    }

    @Builder
    @Getter
    @ToString
    public static class VerifyRecord {
        public IVerifyCallback verifyCallback;
        public int uid;
        public int pid;
        public int requestCode;
        public String pkg;
        public ComponentName componentName;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            VerifyRecord that = (VerifyRecord) o;
            return requestCode == that.requestCode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(requestCode);
        }
    }

}
