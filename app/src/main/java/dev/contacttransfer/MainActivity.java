package dev.contacttransfer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int CONTACT_PERMISSION_REQUEST = 41;
    private static final int REQUEST_IMPORT_CSV = 42;
    private static final int REQUEST_EXPORT_CSV = 43;
    private static final int COLOR_BACKGROUND = 0xFFF5F6FA;
    private static final int COLOR_CARD = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFF1D2433;
    private static final int COLOR_MUTED = 0xFF626B7D;
    private static final int COLOR_ACCENT = 0xFF315CDB;
    private static final String[] DATA_FIELDS = {
            ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4, ContactsContract.Data.DATA5, ContactsContract.Data.DATA6,
            ContactsContract.Data.DATA7, ContactsContract.Data.DATA8, ContactsContract.Data.DATA9,
            ContactsContract.Data.DATA10, ContactsContract.Data.DATA11, ContactsContract.Data.DATA12,
            ContactsContract.Data.DATA13, ContactsContract.Data.DATA14, ContactsContract.Data.DATA15
    };

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<ContactItem> allContacts = new ArrayList<>();
    private final List<ContactItem> contacts = new ArrayList<>();
    private final List<AccountOption> accounts = new ArrayList<>();
    private final List<GroupOption> groups = new ArrayList<>();
    private final Set<Long> selectedIds = new HashSet<>();
    private LinearLayout contactList;
    private TextView statusView;
    private TextView selectionView;
    private Spinner sourceSpinner;
    private Spinner destinationSpinner;
    private Spinner groupSpinner;
    private Button permissionButton;
    private Button refreshButton;
    private Button selectAllButton;
    private Button copyButton;
    private Button moveButton;
    private Button deleteButton;
    private Button importButton;
    private Button exportButton;
    private ProgressBar progressBar;
    private AccountKey activeSourceFilter;
    private GroupOption activeGroupFilter;
    private boolean sourceFilterInitialized;
    private boolean groupFilterInitialized;
    private boolean updatingSourceSpinner;
    private boolean updatingGroupSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
        if (hasContactsPermission()) reloadContacts();
        else showPermissionPrompt();
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BACKGROUND);
        root.setPadding(dp(20), dp(12), dp(20), dp(12));
        View decor = getWindow().getDecorView();
        decor.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(12) + insets.getSystemWindowInsetTop(),
                    dp(20) + insets.getSystemWindowInsetRight(), dp(12) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        decor.requestApplyInsets();
        root.addView(text("Contact Account Transfer", 25, COLOR_TEXT, true), matchWrap());
        TextView intro = text("Copy, move, or delete contacts by account. Import and export group contacts as CSV.", 14, COLOR_MUTED, false);
        intro.setPadding(0, dp(6), 0, dp(10));
        root.addView(intro, matchWrap());

        LinearLayout sourceCard = card();
        sourceCard.addView(text("Show contacts from", 13, COLOR_MUTED, true), matchWrap());
        sourceSpinner = new Spinner(this);
        sourceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingSourceSpinner || parent.getItemAtPosition(position) == null) return;
                SourceOption option = (SourceOption) parent.getItemAtPosition(position);
                if (sourceFilterInitialized && equal(activeSourceFilter, option.key)) return;
                activeSourceFilter = option.key;
                sourceFilterInitialized = true;
                selectedIds.clear();
                applyContactFilter();
                statusView.setText(visibleContactsSummary());
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        sourceCard.addView(sourceSpinner, matchWrap());
        root.addView(sourceCard, matchWrap());

        LinearLayout accountCard = card();
        accountCard.addView(text("Destination account", 13, COLOR_MUTED, true), matchWrap());
        destinationSpinner = new Spinner(this);
        accountCard.addView(destinationSpinner, matchWrap());
        root.addView(accountCard, matchWrap());

        LinearLayout groupCard = card();
        groupCard.addView(text("Group (filter / import / export)", 13, COLOR_MUTED, true), matchWrap());
        groupSpinner = new Spinner(this);
        groupSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingGroupSpinner || parent.getItemAtPosition(position) == null) return;
                GroupOption option = (GroupOption) parent.getItemAtPosition(position);
                if (groupFilterInitialized && equal(activeGroupFilter, option)) return;
                activeGroupFilter = option;
                groupFilterInitialized = true;
                selectedIds.clear();
                applyContactFilter();
                statusView.setText(visibleContactsSummary());
                refreshActionAvailability();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        groupCard.addView(groupSpinner, matchWrap());
        root.addView(groupCard, matchWrap());

        LinearLayout utilityRow = horizontal();
        refreshButton = button("Refresh");
        selectAllButton = button("Select all");
        utilityRow.addView(refreshButton, weighted());
        utilityRow.addView(selectAllButton, weighted());
        root.addView(utilityRow, matchWrap());
        LinearLayout groupActions = horizontal();
        importButton = button("Import group CSV");
        exportButton = button("Export group CSV");
        groupActions.addView(importButton, weighted());
        groupActions.addView(exportButton, weighted());
        root.addView(groupActions, matchWrap());

        permissionButton = button("Allow contact access");
        permissionButton.setVisibility(View.GONE);
        permissionButton.setOnClickListener(view -> requestContactsPermission());
        root.addView(permissionButton, matchWrap());

        LinearLayout listHeader = horizontal();
        TextView contactsLabel = text("Contacts", 18, COLOR_TEXT, true);
        listHeader.addView(contactsLabel, new LinearLayout.LayoutParams(0, dp(40), 1));
        selectionView = text("0 selected", 13, COLOR_MUTED, false);
        listHeader.addView(selectionView, wrapWrap());
        root.addView(listHeader, matchWrap());
        ScrollView scroll = new ScrollView(this);
        contactList = new LinearLayout(this);
        contactList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(contactList, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        statusView = text("", 13, COLOR_MUTED, false);
        statusView.setPadding(0, dp(8), 0, dp(4));
        root.addView(statusView, matchWrap());
        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(-2, dp(4)));

        LinearLayout actions = horizontal();
        copyButton = button("Copy selected");
        moveButton = button("Move selected");
        deleteButton = button("Delete from account");
        copyButton.setOnClickListener(view -> transferSelected(false));
        moveButton.setOnClickListener(view -> confirmMove());
        deleteButton.setOnClickListener(view -> confirmDelete());
        actions.addView(copyButton, weighted());
        actions.addView(moveButton, weighted());
        actions.addView(deleteButton, weighted());
        root.addView(actions, matchWrap());

        refreshButton.setOnClickListener(view -> reloadContacts());
        selectAllButton.setOnClickListener(view -> toggleSelectAll());
        importButton.setOnClickListener(view -> chooseImportFile());
        exportButton.setOnClickListener(view -> chooseExportFile());
        setContentView(root);
        refreshActionAvailability();
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private void showPermissionPrompt() {
        allContacts.clear(); contacts.clear(); accounts.clear(); groups.clear(); selectedIds.clear();
        activeSourceFilter = null; activeGroupFilter = null;
        sourceFilterInitialized = false; groupFilterInitialized = false;
        updateAccountSpinner(); updateSourceSpinner(); updateGroupSpinner(); renderContacts();
        statusView.setText("Allow contact access to manage contacts and groups.");
        permissionButton.setVisibility(View.VISIBLE);
        refreshActionAvailability();
    }

    private boolean hasContactsPermission() {
        return checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestContactsPermission() {
        requestPermissions(new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS}, CONTACT_PERMISSION_REQUEST);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CONTACT_PERMISSION_REQUEST) return;
        if (hasContactsPermission()) { permissionButton.setVisibility(View.GONE); reloadContacts(); }
        else { statusView.setText("Contact access is required to manage contacts."); permissionButton.setVisibility(View.VISIBLE); }
    }

    private void reloadContacts() {
        if (!hasContactsPermission()) { showPermissionPrompt(); return; }
        setBusy(true, "Loading contacts, accounts, and groups…");
        worker.execute(() -> {
            try {
                List<GroupOption> loadedGroups = readGroups();
                List<ContactItem> loadedContacts = readContacts(loadedGroups);
                List<AccountOption> loadedAccounts = readAccounts(loadedContacts);
                runOnUiThread(() -> {
                    allContacts.clear(); allContacts.addAll(loadedContacts);
                    contacts.clear(); accounts.clear(); accounts.addAll(loadedAccounts);
                    groups.clear(); groups.addAll(loadedGroups);
                    updateAccountSpinner(); updateSourceSpinner(); updateGroupSpinner();
                    applyContactFilter(); permissionButton.setVisibility(View.GONE);
                    setBusy(false, visibleContactsSummary());
                });
            } catch (Exception e) {
                runOnUiThread(() -> { setBusy(false, "Could not read contacts: " + errorMessage(e)); showPermissionIfNeeded(); });
            }
        });
    }

    private List<GroupOption> readGroups() {
        List<GroupOption> result = new ArrayList<>();
        String[] projection = {ContactsContract.Groups._ID, ContactsContract.Groups.TITLE,
                ContactsContract.Groups.ACCOUNT_NAME, ContactsContract.Groups.ACCOUNT_TYPE, ContactsContract.Groups.DATA_SET};
        try (Cursor cursor = getContentResolver().query(ContactsContract.Groups.CONTENT_URI, projection,
                ContactsContract.Groups.DELETED + "=0", null, ContactsContract.Groups.TITLE + " COLLATE NOCASE")) {
            if (cursor != null) while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String title = cursor.isNull(1) ? "Untitled group" : cursor.getString(1);
                AccountKey account = new AccountKey(cursor.isNull(2) ? null : cursor.getString(2),
                        cursor.isNull(3) ? null : cursor.getString(3), cursor.isNull(4) ? null : cursor.getString(4));
                result.add(new GroupOption(id, title, account));
            }
        }
        Collections.sort(result, (a, b) -> a.toString().compareToIgnoreCase(b.toString()));
        return result;
    }

    private List<ContactItem> readContacts(List<GroupOption> groupOptions) {
        Map<Long, String> names = new HashMap<>(), fallbacks = new HashMap<>();
        Map<Long, Set<Long>> memberships = new HashMap<>();
        String[] dataProjection = {ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1, ContactsContract.Data.DATA_ROW_ID};
        try (Cursor cursor = getContentResolver().query(ContactsContract.Data.CONTENT_URI, dataProjection,
                null, null, null)) {
            if (cursor != null) while (cursor.moveToNext()) {
                long rawId = cursor.getLong(0);
                String mime = cursor.getString(1);
                String value = cursor.getString(2);
                if (ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE.equals(mime) && value != null && !value.trim().isEmpty()) {
                    names.put(rawId, value.trim());
                } else if ((ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE.equals(mime)
                        || ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE.equals(mime)) && value != null && !value.trim().isEmpty()) {
                    if (!fallbacks.containsKey(rawId)) fallbacks.put(rawId, value.trim());
                } else if (ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE.equals(mime) && !cursor.isNull(2)) {
                    try {
                        long groupId = Long.parseLong(value);
                        memberships.computeIfAbsent(rawId, ignored -> new HashSet<>()).add(groupId);
                    } catch (NumberFormatException ignored) { }
                }
            }
        }
        List<ContactItem> result = new ArrayList<>();
        String[] rawProjection = {ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.DATA_SET};
        try (Cursor cursor = getContentResolver().query(ContactsContract.RawContacts.CONTENT_URI, rawProjection,
                ContactsContract.RawContacts.DELETED + "=0", null, null)) {
            if (cursor != null) while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                AccountKey account = new AccountKey(cursor.isNull(1) ? null : cursor.getString(1),
                        cursor.isNull(2) ? null : cursor.getString(2), cursor.isNull(3) ? null : cursor.getString(3));
                String name = names.get(id);
                if (name == null) name = fallbacks.get(id);
                if (name == null || name.isEmpty()) name = "Unnamed contact";
                Set<Long> groupIds = memberships.get(id);
                result.add(new ContactItem(id, name, account, groupIds == null ? new HashSet<>() : new HashSet<>(groupIds)));
            }
        }
        Collections.sort(result, (left, right) -> left.name.compareToIgnoreCase(right.name));
        return result;
    }

    private List<AccountOption> readAccounts(List<ContactItem> items) {
        Map<AccountKey, AccountOption> unique = new LinkedHashMap<>();
        for (ContactItem item : items) unique.putIfAbsent(item.account, new AccountOption(item.account));
        List<AccountOption> result = new ArrayList<>(unique.values());
        Collections.sort(result, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return result;
    }

    private void updateAccountSpinner() {
        AccountOption previous = selectedAccount();
        ArrayAdapter<AccountOption> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, accounts);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        destinationSpinner.setAdapter(adapter);
        int selected = 0;
        if (previous != null) for (int i = 0; i < accounts.size(); i++) if (accounts.get(i).key.equals(previous.key)) { selected = i; break; }
        if (!accounts.isEmpty()) destinationSpinner.setSelection(selected);
        destinationSpinner.setEnabled(!accounts.isEmpty() && !isBusy());
    }

    private void updateSourceSpinner() {
        AccountKey previous = activeSourceFilter;
        List<SourceOption> options = new ArrayList<>();
        options.add(new SourceOption(null, "All accounts"));
        for (AccountOption account : accounts) options.add(new SourceOption(account.key, account.label));
        int selected = 0;
        if (previous != null) for (int i = 0; i < options.size(); i++) if (options.get(i).key != null && options.get(i).key.equals(previous)) { selected = i; break; }
        ArrayAdapter<SourceOption> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        updatingSourceSpinner = true; sourceSpinner.setAdapter(adapter); sourceSpinner.setSelection(selected); updatingSourceSpinner = false;
        activeSourceFilter = options.get(selected).key; sourceFilterInitialized = true;
        sourceSpinner.setEnabled(options.size() > 1 && !isBusy());
    }

    private void updateGroupSpinner() {
        GroupOption previous = activeGroupFilter;
        List<GroupOption> options = new ArrayList<>();
        options.add(GroupOption.allGroups()); options.addAll(groups);
        int selected = 0;
        if (previous != null) for (int i = 1; i < options.size(); i++) if (options.get(i).id == previous.id) { selected = i; break; }
        ArrayAdapter<GroupOption> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        updatingGroupSpinner = true; groupSpinner.setAdapter(adapter); groupSpinner.setSelection(selected); updatingGroupSpinner = false;
        activeGroupFilter = options.get(selected).isAll ? null : options.get(selected);
        groupFilterInitialized = true;
        groupSpinner.setEnabled(options.size() > 1 && !isBusy());
    }

    private void applyContactFilter() {
        contacts.clear();
        for (ContactItem item : allContacts) {
            if ((activeSourceFilter == null || activeSourceFilter.equals(item.account))
                    && (activeGroupFilter == null || item.groupIds.contains(activeGroupFilter.id))) contacts.add(item);
        }
        selectedIds.retainAll(idsOf(contacts));
        renderContacts();
    }

    private String visibleContactsSummary() {
        String count = contacts.size() + (contacts.size() == 1 ? " contact" : " contacts");
        String source = activeSourceFilter == null ? "all accounts" : new AccountOption(activeSourceFilter).label;
        String group = activeGroupFilter == null ? "all groups" : "group " + activeGroupFilter.title;
        return count + " shown from " + source + " · " + group + ".";
    }

    private void renderContacts() {
        contactList.removeAllViews();
        for (ContactItem item : contacts) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL); row.setBackgroundColor(COLOR_CARD); row.setPadding(dp(10), dp(4), dp(10), dp(4));
            LinearLayout.LayoutParams params = matchWrap(); params.bottomMargin = dp(6);
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(item.name); checkBox.setTextColor(COLOR_TEXT); checkBox.setTextSize(15);
            checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(COLOR_ACCENT));
            checkBox.setChecked(selectedIds.contains(item.id));
            checkBox.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selectedIds.add(item.id); else selectedIds.remove(item.id);
                updateSelectionCount(); refreshActionAvailability();
            });
            row.addView(checkBox, matchWrap());
            TextView accountText = text("From " + item.account.displayLabel(), 12, COLOR_MUTED, false);
            accountText.setPadding(dp(42), 0, 0, dp(6)); row.addView(accountText, matchWrap()); contactList.addView(row, params);
        }
        if (contacts.isEmpty()) {
            TextView empty = text("No contacts found for this account and group filter.", 14, COLOR_MUTED, false);
            empty.setGravity(Gravity.CENTER); empty.setPadding(dp(12), dp(28), dp(12), dp(28)); contactList.addView(empty, matchWrap());
        }
        updateSelectionCount(); refreshActionAvailability();
    }

    private void toggleSelectAll() {
        if (contacts.isEmpty()) return;
        if (selectedIds.size() == contacts.size()) selectedIds.clear(); else { selectedIds.clear(); selectedIds.addAll(idsOf(contacts)); }
        renderContacts();
    }

    private void updateSelectionCount() {
        selectionView.setText(selectedIds.size() + " selected");
        selectAllButton.setText(!contacts.isEmpty() && selectedIds.size() == contacts.size() ? "Clear selection" : "Select all");
    }

    private void confirmMove() {
        List<ContactItem> selected = selectedContacts(); AccountOption destination = selectedAccount();
        if (selected.isEmpty() || destination == null) return;
        new AlertDialog.Builder(this).setTitle("Move contacts?")
                .setMessage("Move " + selected.size() + (selected.size() == 1 ? " contact" : " contacts") + " to " + destination.label + "? Originals are removed only after their copies succeed.")
                .setNegativeButton("Cancel", null).setPositiveButton("Move", (dialog, which) -> transferSelected(true)).show();
    }

    private void confirmDelete() {
        List<ContactItem> selected = selectedContacts();
        if (selected.isEmpty() || activeSourceFilter == null) return;
        String accountLabel = new AccountOption(activeSourceFilter).label;
        new AlertDialog.Builder(this).setTitle("Delete contacts?")
                .setMessage("Permanently delete " + selected.size() + (selected.size() == 1 ? " contact" : " contacts")
                        + " from " + accountLabel + "? Contacts in other accounts will not be changed.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete", (dialog, which) -> deleteSelected(selected, activeSourceFilter)).show();
    }

    private void deleteSelected(List<ContactItem> selected, AccountKey account) {
        for (ContactItem item : selected) if (!item.account.equals(account)) return;
        setBusy(true, "Deleting selected contacts from " + account.displayLabel() + "…");
        worker.execute(() -> {
            int deleted = 0, failed = 0;
            for (ContactItem item : selected) {
                try {
                    StringBuilder where = new StringBuilder(ContactsContract.RawContacts._ID + "=?");
                    List<String> args = new ArrayList<>(); args.add(String.valueOf(item.id));
                    appendAccountConstraint(where, args, ContactsContract.RawContacts.ACCOUNT_NAME, account.name);
                    appendAccountConstraint(where, args, ContactsContract.RawContacts.ACCOUNT_TYPE, account.type);
                    appendAccountConstraint(where, args, ContactsContract.RawContacts.DATA_SET, account.dataSet);
                    int count = getContentResolver().delete(ContactsContract.RawContacts.CONTENT_URI, where.toString(), args.toArray(new String[0]));
                    if (count > 0) deleted++; else failed++;
                } catch (Exception e) { failed++; }
            }
            final int done = deleted, errors = failed;
            runOnUiThread(() -> { selectedIds.clear(); setBusy(false, "Deleted " + done + (done == 1 ? " contact" : " contacts") + " from " + account.displayLabel() + (errors == 0 ? "." : "; " + errors + " failed.")); reloadContacts(); });
        });
    }

    private void appendAccountConstraint(StringBuilder where, List<String> args, String column, String value) {
        if (value == null) where.append(" AND ").append(column).append(" IS NULL");
        else { where.append(" AND ").append(column).append("=?"); args.add(value); }
    }

    private void transferSelected(boolean move) {
        List<ContactItem> selected = selectedContacts(); AccountOption destination = selectedAccount();
        if (selected.isEmpty() || destination == null) return;
        setBusy(true, (move ? "Moving " : "Copying ") + selected.size() + " contact(s)…");
        worker.execute(() -> {
            int succeeded = 0, skipped = 0, failed = 0; String firstError = null;
            for (ContactItem item : selected) {
                if (item.account.equals(destination.key)) { skipped++; continue; }
                try { transferOne(item, destination.key, move); succeeded++; }
                catch (Exception e) { failed++; if (firstError == null) firstError = errorMessage(e); }
            }
            final int done = succeeded, ignored = skipped, errors = failed; final String error = firstError;
            runOnUiThread(() -> {
                selectedIds.clear(); String summary = (move ? "Moved " : "Copied ") + done + (done == 1 ? " contact" : " contacts");
                if (ignored > 0) summary += "; skipped " + ignored + " already in that account";
                if (errors > 0) summary += "; " + errors + " failed: " + error; else summary += ".";
                setBusy(false, summary); reloadContacts();
                if (errors > 0) Toast.makeText(this, "Some contacts could not be transferred.", Toast.LENGTH_LONG).show();
            });
        });
    }

    private void transferOne(ContactItem source, AccountKey destination, boolean move) throws Exception {
        List<ContentValues> rows = readContactData(source.id);
        if (rows.isEmpty()) throw new IllegalStateException("No transferable data found for " + source.name);
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        int rawIndex = operations.size();
        operations.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, destination.name)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, destination.type)
                .withValue(ContactsContract.RawContacts.DATA_SET, destination.dataSet).build());
        for (ContentValues row : rows) {
            ContentProviderOperation.Builder insert = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex);
            for (String key : row.keySet()) insert.withValue(key, row.get(key));
            operations.add(insert.build());
        }
        if (move) operations.add(ContentProviderOperation.newDelete(ContactsContract.RawContacts.CONTENT_URI)
                .withSelection(ContactsContract.RawContacts._ID + "=?", new String[]{String.valueOf(source.id)}).build());
        ContentProviderResult[] results = getContentResolver().applyBatch(ContactsContract.AUTHORITY, operations);
        if (results.length == 0 || results[0].uri == null) throw new IllegalStateException("The contacts provider did not create a destination contact");
    }

    private List<ContentValues> readContactData(long rawContactId) {
        List<ContentValues> rows = new ArrayList<>();
        String[] projection = new String[3 + DATA_FIELDS.length];
        projection[0] = ContactsContract.Data.MIMETYPE; projection[1] = ContactsContract.Data.IS_PRIMARY; projection[2] = ContactsContract.Data.IS_SUPER_PRIMARY;
        System.arraycopy(DATA_FIELDS, 0, projection, 3, DATA_FIELDS.length);
        try (Cursor cursor = getContentResolver().query(ContactsContract.Data.CONTENT_URI, projection,
                ContactsContract.Data.RAW_CONTACT_ID + "=?", new String[]{String.valueOf(rawContactId)}, null)) {
            if (cursor == null) return rows;
            while (cursor.moveToNext()) {
                String mime = cursor.getString(0);
                if (mime == null || ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE.equals(mime)) continue;
                ContentValues values = new ContentValues(); values.put(ContactsContract.Data.MIMETYPE, mime);
                if (!cursor.isNull(1) && cursor.getInt(1) != 0) values.put(ContactsContract.Data.IS_PRIMARY, 1);
                if (!cursor.isNull(2) && cursor.getInt(2) != 0) values.put(ContactsContract.Data.IS_SUPER_PRIMARY, 1);
                for (int i = 0; i < DATA_FIELDS.length; i++) if (!cursor.isNull(i + 3)) putCursorValue(cursor, i + 3, DATA_FIELDS[i], values);
                rows.add(values);
            }
        }
        return rows;
    }

    private void putCursorValue(Cursor cursor, int index, String key, ContentValues values) {
        switch (cursor.getType(index)) {
            case Cursor.FIELD_TYPE_INTEGER: values.put(key, cursor.getLong(index)); break;
            case Cursor.FIELD_TYPE_FLOAT: values.put(key, cursor.getDouble(index)); break;
            case Cursor.FIELD_TYPE_BLOB: values.put(key, cursor.getBlob(index)); break;
            case Cursor.FIELD_TYPE_STRING: values.put(key, cursor.getString(index)); break;
            default: break;
        }
    }

    private void chooseImportFile() {
        if (activeGroupFilter == null) { toast("Select a group before importing."); return; }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("text/*");
        startActivityForResult(intent, REQUEST_IMPORT_CSV);
    }

    private void chooseExportFile() {
        if (activeGroupFilter == null) { toast("Select a group before exporting."); return; }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv"); intent.putExtra(Intent.EXTRA_TITLE, safeFileName(activeGroupFilter.title) + ".csv");
        startActivityForResult(intent, REQUEST_EXPORT_CSV);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final GroupOption group = activeGroupFilter;
        if (group == null) { toast("Select a group first."); return; }
        if (requestCode == REQUEST_IMPORT_CSV) importCsv(uri, group);
        else if (requestCode == REQUEST_EXPORT_CSV) exportCsv(uri, group);
    }

    private void importCsv(Uri uri, GroupOption group) {
        setBusy(true, "Importing contacts into " + group.title + "…");
        worker.execute(() -> {
            int imported = 0;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("Could not open the selected CSV file");
                List<List<String>> rows = parseCsv(input);
                int start = !rows.isEmpty() && !rows.get(0).isEmpty() && "name".equalsIgnoreCase(rows.get(0).get(0).trim()) ? 1 : 0;
                for (int i = start; i < rows.size(); i++) {
                    List<String> row = rows.get(i);
                    String name = field(row, 0).trim();
                    List<String> phones = splitValues(field(row, 1));
                    List<String> emails = splitValues(field(row, 2));
                    if (name.isEmpty() && phones.isEmpty() && emails.isEmpty()) continue;
                    insertImportedContact(group, name, phones, emails);
                    imported++;
                }
            } catch (Exception e) {
                final int count = imported; final String error = errorMessage(e);
                runOnUiThread(() -> { setBusy(false, "Imported " + count + " contact(s); import stopped: " + error); reloadContacts(); });
                return;
            }
            final int count = imported;
            runOnUiThread(() -> { setBusy(false, "Imported " + count + (count == 1 ? " contact" : " contacts") + " into " + group.title + "."); reloadContacts(); });
        });
    }

    private void insertImportedContact(GroupOption group, String name, List<String> phones, List<String> emails) throws Exception {
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        int rawIndex = operations.size();
        operations.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, group.account.name)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, group.account.type)
                .withValue(ContactsContract.RawContacts.DATA_SET, group.account.dataSet).build());
        if (!name.isEmpty()) {
            ContentValues values = new ContentValues(); values.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE);
            values.put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name);
            operations.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                    .withValues(values).build());
        }
        for (String phone : phones) if (!phone.trim().isEmpty()) {
            ContentValues values = new ContentValues(); values.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);
            values.put(ContactsContract.CommonDataKinds.Phone.NUMBER, phone.trim());
            values.put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_OTHER);
            operations.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex).withValues(values).build());
        }
        for (String email : emails) if (!email.trim().isEmpty()) {
            ContentValues values = new ContentValues(); values.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE);
            values.put(ContactsContract.CommonDataKinds.Email.ADDRESS, email.trim());
            values.put(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_OTHER);
            operations.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex).withValues(values).build());
        }
        ContentValues membership = new ContentValues(); membership.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE);
        membership.put(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, group.id);
        operations.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex).withValues(membership).build());
        ContentProviderResult[] result = getContentResolver().applyBatch(ContactsContract.AUTHORITY, operations);
        if (result.length == 0 || result[0].uri == null) throw new IllegalStateException("The contacts provider did not create the imported contact");
    }

    private void exportCsv(Uri uri, GroupOption group) {
        List<ContactItem> snapshot = new ArrayList<>();
        for (ContactItem item : allContacts) if (item.groupIds.contains(group.id)) snapshot.add(item);
        setBusy(true, "Exporting " + snapshot.size() + " contact(s) from " + group.title + "…");
        worker.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri);
                 BufferedWriter writer = output == null ? null : new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
                if (writer == null) throw new IllegalStateException("Could not create the selected CSV file");
                writer.write("Name,Phones,Emails\r\n");
                for (ContactItem item : snapshot) {
                    List<ContentValues> values = readContactData(item.id);
                    List<String> phones = new ArrayList<>(), emails = new ArrayList<>();
                    String name = item.name;
                    for (ContentValues value : values) {
                        String mime = value.getAsString(ContactsContract.Data.MIMETYPE);
                        String data = value.getAsString(ContactsContract.Data.DATA1);
                        if (data == null) continue;
                        if (ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE.equals(mime)) name = data;
                        else if (ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE.equals(mime)) phones.add(data);
                        else if (ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE.equals(mime)) emails.add(data);
                    }
                    writer.write(csv(name) + "," + csv(joinValues(phones)) + "," + csv(joinValues(emails)) + "\r\n");
                }
                writer.flush();
            } catch (Exception e) {
                final String error = errorMessage(e); runOnUiThread(() -> setBusy(false, "Export failed: " + error)); return;
            }
            runOnUiThread(() -> setBusy(false, "Exported " + snapshot.size() + (snapshot.size() == 1 ? " contact" : " contacts") + " from " + group.title + "."));
        });
    }

    private List<List<String>> parseCsv(InputStream input) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            List<String> row = new ArrayList<>(); StringBuilder field = new StringBuilder();
            boolean quoted = false; int value;
            while ((value = reader.read()) != -1) {
                char c = (char) value;
                if (quoted) {
                    if (c == '"') {
                        reader.mark(1); int next = reader.read();
                        if (next == '"') field.append('"');
                        else { quoted = false; if (next != -1) reader.reset(); }
                    } else field.append(c);
                } else if (c == '"' && field.length() == 0) quoted = true;
                else if (c == ',') { row.add(field.toString()); field.setLength(0); }
                else if (c == '\n' || c == '\r') {
                    if (c == '\r') { reader.mark(1); int next = reader.read(); if (next != '\n' && next != -1) reader.reset(); }
                    row.add(field.toString()); field.setLength(0);
                    if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
                    row = new ArrayList<>();
                } else field.append(c);
            }
            if (quoted) throw new IllegalArgumentException("CSV contains an unclosed quoted field");
            if (field.length() > 0 || !row.isEmpty()) { row.add(field.toString()); rows.add(row); }
        }
        return rows;
    }

    private String csv(String value) { return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\""; }
    private String joinValues(List<String> values) { StringBuilder result = new StringBuilder(); for (String value : values) { if (result.length() > 0) result.append('\n'); result.append(value); } return result.toString(); }
    private List<String> splitValues(String value) { List<String> result = new ArrayList<>(); if (value == null || value.isEmpty()) return result; Collections.addAll(result, value.split("\\r?\\n", -1)); return result; }
    private String field(List<String> row, int index) { return index < row.size() && row.get(index) != null ? row.get(index) : ""; }
    private String safeFileName(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }

    private void showPermissionIfNeeded() { if (!hasContactsPermission()) permissionButton.setVisibility(View.VISIBLE); }
    private void setBusy(boolean busy, String message) {
        statusView.setText(message); progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        refreshActionAvailability(); permissionButton.setEnabled(!busy);
    }
    private boolean isBusy() { return progressBar != null && progressBar.getVisibility() == View.VISIBLE; }
    private void refreshActionAvailability() {
        if (refreshButton == null) return;
        boolean busy = isBusy();
        refreshButton.setEnabled(!busy && hasContactsPermission());
        selectAllButton.setEnabled(!busy && !contacts.isEmpty());
        boolean selected = !selectedIds.isEmpty();
        copyButton.setEnabled(!busy && selected && selectedAccount() != null);
        moveButton.setEnabled(!busy && selected && selectedAccount() != null);
        deleteButton.setEnabled(!busy && selected && activeSourceFilter != null && hasContactsPermission());
        importButton.setEnabled(!busy && activeGroupFilter != null && hasContactsPermission());
        exportButton.setEnabled(!busy && activeGroupFilter != null && hasContactsPermission());
        sourceSpinner.setEnabled(!busy && sourceSpinner.getAdapter() != null && sourceSpinner.getAdapter().getCount() > 1);
        destinationSpinner.setEnabled(!busy && !accounts.isEmpty());
        groupSpinner.setEnabled(!busy && groupSpinner.getAdapter() != null && groupSpinner.getAdapter().getCount() > 1);
    }
    private AccountOption selectedAccount() { return destinationSpinner == null || destinationSpinner.getSelectedItem() == null ? null : (AccountOption) destinationSpinner.getSelectedItem(); }
    private List<ContactItem> selectedContacts() { List<ContactItem> result = new ArrayList<>(); for (ContactItem item : contacts) if (selectedIds.contains(item.id)) result.add(item); return result; }
    private Set<Long> idsOf(List<ContactItem> items) { Set<Long> result = new HashSet<>(); for (ContactItem item : items) result.add(item.id); return result; }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    private LinearLayout card() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setBackgroundColor(COLOR_CARD); layout.setPadding(dp(14), dp(6), dp(14), dp(6)); LinearLayout.LayoutParams params = matchWrap(); params.bottomMargin = dp(6); layout.setLayoutParams(params); return layout; }
    private TextView text(String value, float size, int color, boolean bold) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view; }
    private Button button(String label) { Button view = new Button(this); view.setText(label); view.setAllCaps(false); view.setTextColor(COLOR_ACCENT); return view; }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams wrapWrap() { return new LinearLayout.LayoutParams(-2, -2); }
    private LinearLayout.LayoutParams weighted() { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(dp(2), dp(2), dp(2), dp(2)); return params; }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private String errorMessage(Exception e) { String message = e.getMessage(); return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message; }

    private static final class ContactItem {
        final long id; final String name; final AccountKey account; final Set<Long> groupIds;
        ContactItem(long id, String name, AccountKey account, Set<Long> groupIds) { this.id = id; this.name = name; this.account = account; this.groupIds = groupIds; }
    }
    private static final class AccountKey {
        final String name, type, dataSet;
        AccountKey(String name, String type, String dataSet) { this.name = name; this.type = type; this.dataSet = dataSet; }
        String displayLabel() {
            if (name == null && type == null) return dataSet == null || dataSet.isEmpty() ? "On this device" : "On this device · " + dataSet;
            String provider = providerLabel(type); String label = name == null || name.isEmpty() ? (provider == null ? type : provider) : (provider == null ? name : name + " · " + provider);
            return dataSet == null || dataSet.isEmpty() ? label : label + " · " + dataSet;
        }
        @Override public boolean equals(Object other) { if (!(other instanceof AccountKey)) return false; AccountKey that = (AccountKey) other; return equal(name, that.name) && equal(type, that.type) && equal(dataSet, that.dataSet); }
        @Override public int hashCode() { int result = name == null ? 0 : name.hashCode(); result = 31 * result + (type == null ? 0 : type.hashCode()); return 31 * result + (dataSet == null ? 0 : dataSet.hashCode()); }
    }
    private static final class AccountOption {
        final AccountKey key; final String label; AccountOption(AccountKey key) { this.key = key; label = key.displayLabel(); }
        @Override public String toString() { return label; }
    }
    private static final class SourceOption {
        final AccountKey key; final String label; SourceOption(AccountKey key, String label) { this.key = key; this.label = label; }
        @Override public String toString() { return label; }
    }
    private static final class GroupOption {
        final long id; final String title; final AccountKey account; final boolean isAll;
        GroupOption(long id, String title, AccountKey account) { this.id = id; this.title = title; this.account = account; isAll = false; }
        private GroupOption() { id = -1; title = "All groups"; account = null; isAll = true; }
        static GroupOption allGroups() { return new GroupOption(); }
        @Override public boolean equals(Object other) { if (!(other instanceof GroupOption)) return false; GroupOption that = (GroupOption) other; return isAll == that.isAll && (isAll || id == that.id); }
        @Override public int hashCode() { return isAll ? 0 : Long.valueOf(id).hashCode(); }
        @Override public String toString() { return isAll ? title : title + " · " + account.displayLabel(); }
    }
    private static boolean equal(Object a, Object b) { return a == null ? b == null : a.equals(b); }
    private static String providerLabel(String type) {
        if (type == null || type.isEmpty()) return null;
        if ("com.google".equals(type)) return "Google";
        if ("at.bitfire.davdroid".equals(type) || "org.dmfs.caldav".equals(type)) return "DAVx⁵";
        if ("com.android.exchange".equals(type)) return "Exchange";
        return type;
    }
}
