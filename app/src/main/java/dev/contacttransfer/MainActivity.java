package dev.contacttransfer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

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
    private static final int COLOR_BACKGROUND = 0xFFF5F6FA;
    private static final int COLOR_CARD = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFF1D2433;
    private static final int COLOR_MUTED = 0xFF626B7D;
    private static final int COLOR_ACCENT = 0xFF315CDB;
    private static final String[] DATA_FIELDS = {
            ContactsContract.Data.DATA1, ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3, ContactsContract.Data.DATA4,
            ContactsContract.Data.DATA5, ContactsContract.Data.DATA6,
            ContactsContract.Data.DATA7, ContactsContract.Data.DATA8,
            ContactsContract.Data.DATA9, ContactsContract.Data.DATA10,
            ContactsContract.Data.DATA11, ContactsContract.Data.DATA12,
            ContactsContract.Data.DATA13, ContactsContract.Data.DATA14,
            ContactsContract.Data.DATA15
    };

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<ContactItem> allContacts = new ArrayList<>();
    private final List<ContactItem> contacts = new ArrayList<>();
    private final List<AccountOption> accounts = new ArrayList<>();
    private final Set<Long> selectedIds = new HashSet<>();

    private LinearLayout contactList;
    private TextView statusView;
    private TextView selectionView;
    private Spinner sourceSpinner;
    private Spinner destinationSpinner;
    private Button permissionButton;
    private Button refreshButton;
    private Button selectAllButton;
    private Button copyButton;
    private Button moveButton;
    private ProgressBar progressBar;
    private AccountKey activeSourceFilter;
    private boolean sourceFilterInitialized;
    private boolean updatingSourceSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
        if (hasContactsPermission()) {
            reloadContacts();
        } else {
            showPermissionPrompt();
        }
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
            int left = insets.getSystemWindowInsetLeft();
            int top = insets.getSystemWindowInsetTop();
            int right = insets.getSystemWindowInsetRight();
            int bottom = insets.getSystemWindowInsetBottom();
            root.setPadding(dp(20) + left, dp(12) + top, dp(20) + right, dp(12) + bottom);
            return insets;
        });
        decor.requestApplyInsets();

        TextView title = text("Contact Account Transfer", 25, COLOR_TEXT, true);
        root.addView(title, matchWrap());

        TextView intro = text("Copy or move contacts between your device and synced accounts.", 14, COLOR_MUTED, false);
        intro.setPadding(0, dp(6), 0, dp(14));
        root.addView(intro, matchWrap());

        LinearLayout sourceCard = card();
        TextView sourceLabel = text("Show contacts from", 13, COLOR_MUTED, true);
        sourceCard.addView(sourceLabel, matchWrap());
        sourceSpinner = new Spinner(this);
        sourceSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (updatingSourceSpinner || parent.getItemAtPosition(position) == null) return;
                SourceOption option = (SourceOption) parent.getItemAtPosition(position);
                if (sourceFilterInitialized && equal(activeSourceFilter, option.key)) return;
                activeSourceFilter = option.key;
                sourceFilterInitialized = true;
                selectedIds.clear();
                applyContactFilter();
                statusView.setText(visibleContactsSummary());
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        sourceCard.addView(sourceSpinner, matchWrap());
        root.addView(sourceCard, matchWrap());

        LinearLayout accountCard = card();
        TextView destinationLabel = text("Destination account", 13, COLOR_MUTED, true);
        accountCard.addView(destinationLabel, matchWrap());
        destinationSpinner = new Spinner(this);
        accountCard.addView(destinationSpinner, matchWrap());
        root.addView(accountCard, matchWrap());

        LinearLayout utilityRow = new LinearLayout(this);
        utilityRow.setOrientation(LinearLayout.HORIZONTAL);
        utilityRow.setGravity(Gravity.CENTER_VERTICAL);
        refreshButton = button("Refresh");
        selectAllButton = button("Select all");
        utilityRow.addView(refreshButton, weighted());
        utilityRow.addView(selectAllButton, weighted());
        root.addView(utilityRow, matchWrap());

        permissionButton = button("Allow contact access");
        permissionButton.setVisibility(View.GONE);
        permissionButton.setOnClickListener(view -> requestContactsPermission());
        root.addView(permissionButton, matchWrap());

        LinearLayout listHeader = new LinearLayout(this);
        listHeader.setOrientation(LinearLayout.HORIZONTAL);
        listHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView contactsLabel = text("Contacts", 18, COLOR_TEXT, true);
        listHeader.addView(contactsLabel, new LinearLayout.LayoutParams(0, dp(44), 1));
        selectionView = text("0 selected", 13, COLOR_MUTED, false);
        listHeader.addView(selectionView, wrapWrap());
        root.addView(listHeader, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        contactList = new LinearLayout(this);
        contactList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(contactList, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        root.addView(scroll, scrollParams);

        statusView = text("", 13, COLOR_MUTED, false);
        statusView.setPadding(0, dp(8), 0, dp(4));
        root.addView(statusView, matchWrap());

        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(4)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        copyButton = button("Copy selected");
        moveButton = button("Move selected");
        copyButton.setOnClickListener(view -> transferSelected(false));
        moveButton.setOnClickListener(view -> confirmMove());
        actions.addView(copyButton, weighted());
        actions.addView(moveButton, weighted());
        root.addView(actions, matchWrap());

        refreshButton.setOnClickListener(view -> reloadContacts());
        selectAllButton.setOnClickListener(view -> toggleSelectAll());
        setContentView(root);
        refreshActionAvailability();
    }

    private void showPermissionPrompt() {
        allContacts.clear();
        contacts.clear();
        accounts.clear();
        selectedIds.clear();
        activeSourceFilter = null;
        sourceFilterInitialized = false;
        updateAccountSpinner();
        updateSourceSpinner();
        renderContacts();
        statusView.setText("Allow contact access to choose contacts and destination accounts.");
        permissionButton.setVisibility(View.VISIBLE);
        refreshActionAvailability();
    }

    private boolean hasContactsPermission() {
        return checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestContactsPermission() {
        requestPermissions(new String[]{
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS
        }, CONTACT_PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CONTACT_PERMISSION_REQUEST) return;
        if (hasContactsPermission()) {
            permissionButton.setVisibility(View.GONE);
            reloadContacts();
        } else {
            statusView.setText("Contact access is required to copy or move contacts.");
            permissionButton.setVisibility(View.VISIBLE);
        }
    }

    private void reloadContacts() {
        if (!hasContactsPermission()) {
            showPermissionPrompt();
            return;
        }
        setBusy(true, "Loading contacts and accounts…");
        worker.execute(() -> {
            try {
                List<ContactItem> loadedContacts = readContacts();
                List<AccountOption> loadedAccounts = readAccounts(loadedContacts);
                runOnUiThread(() -> {
                    allContacts.clear();
                    allContacts.addAll(loadedContacts);
                    contacts.clear();
                    accounts.clear();
                    accounts.addAll(loadedAccounts);
                    updateAccountSpinner();
                    updateSourceSpinner();
                    applyContactFilter();
                    permissionButton.setVisibility(View.GONE);
                    setBusy(false, visibleContactsSummary());
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false, "Could not read contacts: " + errorMessage(e));
                    showPermissionIfNeeded();
                });
            }
        });
    }

    private List<ContactItem> readContacts() {
        Map<Long, String> names = new HashMap<>();
        Map<Long, String> fallbacks = new HashMap<>();
        String[] dataProjection = {
                ContactsContract.Data.RAW_CONTACT_ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1
        };
        try (Cursor cursor = getContentResolver().query(
                ContactsContract.Data.CONTENT_URI,
                dataProjection,
                null,
                null,
                null)) {
            if (cursor != null) {
                int rawIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID);
                int mimeIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE);
                int dataIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1);
                while (cursor.moveToNext()) {
                    long rawId = cursor.getLong(rawIdIndex);
                    String mime = cursor.getString(mimeIndex);
                    String value = cursor.getString(dataIndex);
                    if (value == null || value.trim().isEmpty()) continue;
                    if (ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE.equals(mime)) {
                        names.put(rawId, value.trim());
                    } else if (ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE.equals(mime)
                            || ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE.equals(mime)) {
                        if (!fallbacks.containsKey(rawId)) fallbacks.put(rawId, value.trim());
                    }
                }
            }
        }

        List<ContactItem> result = new ArrayList<>();
        String[] rawProjection = {
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.DATA_SET
        };
        try (Cursor cursor = getContentResolver().query(
                ContactsContract.RawContacts.CONTENT_URI,
                rawProjection,
                ContactsContract.RawContacts.DELETED + "=0",
                null,
                null)) {
            if (cursor != null) {
                int idIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID);
                int nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_NAME);
                int typeIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE);
                int dataSetIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.DATA_SET);
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idIndex);
                    String accountName = cursor.isNull(nameIndex) ? null : cursor.getString(nameIndex);
                    String accountType = cursor.isNull(typeIndex) ? null : cursor.getString(typeIndex);
                    String dataSet = cursor.isNull(dataSetIndex) ? null : cursor.getString(dataSetIndex);
                    String displayName = names.get(id);
                    if (displayName == null) displayName = fallbacks.get(id);
                    if (displayName == null || displayName.isEmpty()) displayName = "Unnamed contact";
                    result.add(new ContactItem(id, displayName, new AccountKey(accountName, accountType, dataSet)));
                }
            }
        }
        Collections.sort(result, (left, right) -> left.name.compareToIgnoreCase(right.name));
        return result;
    }

    private List<AccountOption> readAccounts(List<ContactItem> contactItems) {
        Map<AccountKey, AccountOption> unique = new LinkedHashMap<>();
        for (ContactItem item : contactItems) {
            if (!unique.containsKey(item.account)) unique.put(item.account, new AccountOption(item.account));
        }
        List<AccountOption> result = new ArrayList<>(unique.values());
        Collections.sort(result, (left, right) -> left.label.compareToIgnoreCase(right.label));
        return result;
    }

    private void updateAccountSpinner() {
        AccountOption previous = selectedAccount();
        ArrayAdapter<AccountOption> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, accounts);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        destinationSpinner.setAdapter(adapter);
        int selectedIndex = 0;
        if (previous != null) {
            for (int i = 0; i < accounts.size(); i++) {
                if (accounts.get(i).key.equals(previous.key)) {
                    selectedIndex = i;
                    break;
                }
            }
        }
        if (!accounts.isEmpty()) destinationSpinner.setSelection(selectedIndex);
        destinationSpinner.setEnabled(!accounts.isEmpty() && !isBusy());
    }

    private void updateSourceSpinner() {
        AccountKey previousFilter = activeSourceFilter;
        List<SourceOption> options = new ArrayList<>();
        options.add(new SourceOption(null, "All accounts"));
        for (AccountOption account : accounts) {
            options.add(new SourceOption(account.key, account.label));
        }

        int selectedIndex = 0;
        if (previousFilter != null) {
            for (int i = 0; i < options.size(); i++) {
                if (options.get(i).key != null && options.get(i).key.equals(previousFilter)) {
                    selectedIndex = i;
                    break;
                }
            }
        }

        ArrayAdapter<SourceOption> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        updatingSourceSpinner = true;
        sourceSpinner.setAdapter(adapter);
        sourceSpinner.setSelection(selectedIndex);
        updatingSourceSpinner = false;

        AccountKey selectedFilter = options.get(selectedIndex).key;
        boolean filterChanged = !sourceFilterInitialized || !equal(activeSourceFilter, selectedFilter);
        activeSourceFilter = selectedFilter;
        sourceFilterInitialized = true;
        if (filterChanged) selectedIds.retainAll(idsOf(allContacts));
        sourceSpinner.setEnabled(options.size() > 1 && !isBusy());
    }

    private void applyContactFilter() {
        contacts.clear();
        for (ContactItem item : allContacts) {
            if (activeSourceFilter == null || activeSourceFilter.equals(item.account)) contacts.add(item);
        }
        selectedIds.retainAll(idsOf(contacts));
        renderContacts();
    }

    private String visibleContactsSummary() {
        String count = contacts.size() + (contacts.size() == 1 ? " contact" : " contacts");
        String source = activeSourceFilter == null
                ? "all accounts"
                : new AccountOption(activeSourceFilter).label;
        return count + " shown from " + source + ".";
    }

    private void renderContacts() {
        contactList.removeAllViews();
        for (ContactItem item : contacts) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundColor(COLOR_CARD);
            row.setPadding(dp(10), dp(4), dp(10), dp(4));
            LinearLayout.LayoutParams rowParams = matchWrap();
            rowParams.bottomMargin = dp(6);

            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(item.name);
            checkBox.setTextColor(COLOR_TEXT);
            checkBox.setTextSize(15);
            checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(COLOR_ACCENT));
            checkBox.setChecked(selectedIds.contains(item.id));
            checkBox.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selectedIds.add(item.id);
                else selectedIds.remove(item.id);
                updateSelectionCount();
                refreshActionAvailability();
            });
            row.addView(checkBox, matchWrap());

            TextView accountText = text("From " + item.account.displayLabel(), 12, COLOR_MUTED, false);
            accountText.setPadding(dp(42), 0, 0, dp(6));
            row.addView(accountText, matchWrap());
            contactList.addView(row, rowParams);
        }
        if (contacts.isEmpty()) {
            TextView empty = text("No contacts found. Add contacts to an account, then refresh.", 14, COLOR_MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(28), dp(12), dp(28));
            contactList.addView(empty, matchWrap());
        }
        updateSelectionCount();
        refreshActionAvailability();
    }

    private void toggleSelectAll() {
        if (contacts.isEmpty()) return;
        if (selectedIds.size() == contacts.size()) {
            selectedIds.clear();
        } else {
            selectedIds.clear();
            selectedIds.addAll(idsOf(contacts));
        }
        renderContacts();
    }

    private void updateSelectionCount() {
        selectionView.setText(selectedIds.size() + " selected");
        selectAllButton.setText(!contacts.isEmpty() && selectedIds.size() == contacts.size() ? "Clear selection" : "Select all");
    }

    private void confirmMove() {
        List<ContactItem> selected = selectedContacts();
        AccountOption destination = selectedAccount();
        if (selected.isEmpty() || destination == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Move contacts?")
                .setMessage("Move " + selected.size() + (selected.size() == 1 ? " contact" : " contacts")
                        + " to " + destination.label + "? Each original is removed only after its copy succeeds.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Move", (dialog, which) -> transferSelected(true))
                .show();
    }

    private void transferSelected(boolean move) {
        List<ContactItem> selected = selectedContacts();
        AccountOption destination = selectedAccount();
        if (selected.isEmpty() || destination == null) return;
        setBusy(true, (move ? "Moving " : "Copying ") + selected.size() + " contact(s)…");
        worker.execute(() -> {
            int succeeded = 0;
            int skipped = 0;
            int failed = 0;
            String firstError = null;
            for (ContactItem item : selected) {
                if (item.account.equals(destination.key)) {
                    skipped++;
                    continue;
                }
                try {
                    transferOne(item, destination.key, move);
                    succeeded++;
                } catch (Exception e) {
                    failed++;
                    if (firstError == null) firstError = errorMessage(e);
                }
            }
            final int done = succeeded;
            final int ignored = skipped;
            final int errors = failed;
            final String error = firstError;
            runOnUiThread(() -> {
                selectedIds.clear();
                String summary = (move ? "Moved " : "Copied ") + done
                        + (done == 1 ? " contact" : " contacts");
                if (ignored > 0) summary += "; skipped " + ignored + " already in that account";
                if (errors > 0) summary += "; " + errors + " failed" + (error == null ? "." : ": " + error);
                else summary += ".";
                setBusy(false, summary);
                reloadContacts();
                if (errors > 0) Toast.makeText(this, "Some contacts could not be transferred.", Toast.LENGTH_LONG).show();
            });
        });
    }

    private void transferOne(ContactItem source, AccountKey destination, boolean move) throws Exception {
        List<ContentValues> rows = readContactData(source.id);
        if (rows.isEmpty()) throw new IllegalStateException("No transferable data found for " + source.name);

        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        int newRawContactIndex = operations.size();
        operations.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, destination.name)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, destination.type)
                .withValue(ContactsContract.RawContacts.DATA_SET, destination.dataSet)
                .build());

        for (ContentValues row : rows) {
            ContentProviderOperation.Builder insert = ContentProviderOperation
                    .newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, newRawContactIndex);
            for (String key : row.keySet()) insert.withValue(key, row.get(key));
            operations.add(insert.build());
        }

        if (move) {
            operations.add(ContentProviderOperation.newDelete(ContactsContract.RawContacts.CONTENT_URI)
                    .withSelection(ContactsContract.RawContacts._ID + "=?", new String[]{String.valueOf(source.id)})
                    .build());
        }

        ContentProviderResult[] results = getContentResolver().applyBatch(ContactsContract.AUTHORITY, operations);
        if (results.length == 0 || results[0].uri == null) {
            throw new IllegalStateException("The contacts provider did not create a destination contact");
        }
    }

    private List<ContentValues> readContactData(long rawContactId) {
        List<ContentValues> rows = new ArrayList<>();
        String[] projection = new String[3 + DATA_FIELDS.length];
        projection[0] = ContactsContract.Data.MIMETYPE;
        projection[1] = ContactsContract.Data.IS_PRIMARY;
        projection[2] = ContactsContract.Data.IS_SUPER_PRIMARY;
        System.arraycopy(DATA_FIELDS, 0, projection, 3, DATA_FIELDS.length);
        String selection = ContactsContract.Data.RAW_CONTACT_ID + "=?";
        String[] selectionArgs = {String.valueOf(rawContactId)};
        try (Cursor cursor = getContentResolver().query(
                ContactsContract.Data.CONTENT_URI, projection, selection, selectionArgs, null)) {
            if (cursor == null) return rows;
            while (cursor.moveToNext()) {
                String mimeType = cursor.getString(0);
                if (mimeType == null || ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE.equals(mimeType)) {
                    continue;
                }
                ContentValues values = new ContentValues();
                values.put(ContactsContract.Data.MIMETYPE, mimeType);
                copyIfTrue(cursor, 1, ContactsContract.Data.IS_PRIMARY, values);
                copyIfTrue(cursor, 2, ContactsContract.Data.IS_SUPER_PRIMARY, values);
                for (int i = 0; i < DATA_FIELDS.length; i++) {
                    int columnIndex = 3 + i;
                    if (!cursor.isNull(columnIndex)) {
                        putCursorValue(cursor, columnIndex, DATA_FIELDS[i], values);
                    }
                }
                rows.add(values);
            }
        }
        return rows;
    }

    private void copyIfTrue(Cursor cursor, int index, String key, ContentValues values) {
        if (!cursor.isNull(index) && cursor.getInt(index) != 0) values.put(key, 1);
    }

    private void putCursorValue(Cursor cursor, int index, String key, ContentValues values) {
        switch (cursor.getType(index)) {
            case Cursor.FIELD_TYPE_INTEGER:
                values.put(key, cursor.getLong(index));
                break;
            case Cursor.FIELD_TYPE_FLOAT:
                values.put(key, cursor.getDouble(index));
                break;
            case Cursor.FIELD_TYPE_BLOB:
                values.put(key, cursor.getBlob(index));
                break;
            case Cursor.FIELD_TYPE_STRING:
                values.put(key, cursor.getString(index));
                break;
            default:
                break;
        }
    }

    private void showPermissionIfNeeded() {
        if (!hasContactsPermission()) {
            permissionButton.setVisibility(View.VISIBLE);
        }
    }

    private void setBusy(boolean busy, String message) {
        statusView.setText(message);
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        refreshButton.setEnabled(!busy && hasContactsPermission());
        selectAllButton.setEnabled(!busy && !contacts.isEmpty());
        copyButton.setEnabled(!busy && !selectedIds.isEmpty() && selectedAccount() != null);
        moveButton.setEnabled(!busy && !selectedIds.isEmpty() && selectedAccount() != null);
        sourceSpinner.setEnabled(!busy && sourceSpinner.getAdapter() != null
                && sourceSpinner.getAdapter().getCount() > 1);
        destinationSpinner.setEnabled(!busy && !accounts.isEmpty());
        permissionButton.setEnabled(!busy);
    }

    private boolean isBusy() {
        return progressBar != null && progressBar.getVisibility() == View.VISIBLE;
    }

    private void refreshActionAvailability() {
        if (refreshButton == null) return;
        boolean busy = isBusy();
        refreshButton.setEnabled(!busy && hasContactsPermission());
        selectAllButton.setEnabled(!busy && !contacts.isEmpty());
        copyButton.setEnabled(!busy && !selectedIds.isEmpty() && selectedAccount() != null);
        moveButton.setEnabled(!busy && !selectedIds.isEmpty() && selectedAccount() != null);
        sourceSpinner.setEnabled(!busy && sourceSpinner.getAdapter() != null
                && sourceSpinner.getAdapter().getCount() > 1);
        destinationSpinner.setEnabled(!busy && !accounts.isEmpty());
    }

    private AccountOption selectedAccount() {
        if (destinationSpinner == null || destinationSpinner.getSelectedItem() == null) return null;
        return (AccountOption) destinationSpinner.getSelectedItem();
    }

    private List<ContactItem> selectedContacts() {
        List<ContactItem> result = new ArrayList<>();
        for (ContactItem item : contacts) if (selectedIds.contains(item.id)) result.add(item);
        return result;
    }

    private Set<Long> idsOf(List<ContactItem> items) {
        Set<Long> ids = new HashSet<>();
        for (ContactItem item : items) ids.add(item.id);
        return ids;
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(COLOR_CARD);
        layout.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(10);
        layout.setLayoutParams(params);
        return layout;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String label) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setTextColor(COLOR_ACCENT);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(dp(2), dp(4), dp(2), dp(4));
        return params;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String errorMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static final class ContactItem {
        final long id;
        final String name;
        final AccountKey account;

        ContactItem(long id, String name, AccountKey account) {
            this.id = id;
            this.name = name;
            this.account = account;
        }
    }

    private static final class AccountKey {
        final String name;
        final String type;
        final String dataSet;

        AccountKey(String name, String type, String dataSet) {
            this.name = name;
            this.type = type;
            this.dataSet = dataSet;
        }

        String displayLabel() {
            if (name == null && type == null) {
                return dataSet == null || dataSet.isEmpty() ? "On this device" : "On this device · " + dataSet;
            }
            String provider = providerLabel(type);
            String label;
            if (name == null || name.isEmpty()) label = provider == null ? type : provider;
            else label = provider == null ? name : name + " · " + provider;
            if (dataSet != null && !dataSet.isEmpty()) label += " · " + dataSet;
            return label;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof AccountKey)) return false;
            AccountKey that = (AccountKey) other;
            return equal(name, that.name) && equal(type, that.type) && equal(dataSet, that.dataSet);
        }

        @Override
        public int hashCode() {
            int result = (name == null ? 0 : name.hashCode());
            result = 31 * result + (type == null ? 0 : type.hashCode());
            return 31 * result + (dataSet == null ? 0 : dataSet.hashCode());
        }
    }

    private static final class AccountOption {
        final AccountKey key;
        final String label;

        AccountOption(AccountKey key) {
            this.key = key;
            this.label = key.displayLabel();
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class SourceOption {
        final AccountKey key;
        final String label;

        SourceOption(AccountKey key, String label) {
            this.key = key;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static boolean equal(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String providerLabel(String type) {
        if (type == null || type.isEmpty()) return null;
        if ("com.google".equals(type)) return "Google";
        if ("at.bitfire.davdroid".equals(type) || "org.dmfs.caldav".equals(type)) return "DAVx⁵";
        if ("com.android.exchange".equals(type)) return "Exchange";
        return type;
    }
}

