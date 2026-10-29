package in.garvitmodule.billing

import android.content.ContentValues
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.gms.tasks.Tasks
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BillItem(val name: String, val qty: Double, val rate: Double) {
    val amount: Double get() = qty * rate
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GarvitBillingApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarvitBillingApp() {
    val context = LocalContext.current
    var customer by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var itemName by remember { mutableStateOf("") }
    var catalogRate by remember { mutableStateOf("0") }
    var catalogItems by remember {
        mutableStateOf(loadCatalog(context))
    }
    var qtyText by remember { mutableStateOf("1") }
    var rateText by remember { mutableStateOf("0") }
    var gstEnabled by remember { mutableStateOf(false) }
    var gstRate by remember { mutableStateOf("18") }
    var invoiceNo by remember {
        mutableIntStateOf(context.getSharedPreferences("garvit_billing", android.content.Context.MODE_PRIVATE)
            .getInt("next_invoice_no", 1))
    }
    var receivedText by remember { mutableStateOf("0") }
    var upiId by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(listOf<BillItem>()) }
    var status by remember { mutableStateOf("Ready to create your first bill.") }

    val subtotal = items.sumOf { it.amount }
    val gst = if (gstEnabled) subtotal * ((gstRate.toDoubleOrNull() ?: 0.0) / 100.0) else 0.0
    val total = subtotal + gst
    val received = receivedText.toDoubleOrNull() ?: 0.0
    val date = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault()).format(Date())

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("GARVIT MODULE", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFFF5E4E5)
                    )
                )
            }
        ) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("MODULE & SMPS • Billing", style = MaterialTheme.typography.titleMedium)
                Text("Invoice No. GM-${invoiceNo.toString().padStart(4, '0')}    •    $date")
                OutlinedTextField(customer, { customer = it }, label = { Text("Customer name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it }, label = { Text("Customer mobile") }, modifier = Modifier.fillMaxWidth())
                Divider()
                Text("Add item", fontWeight = FontWeight.Bold)
                OutlinedTextField(itemName, { itemName = it }, label = { Text("Item name (e.g. MODULE, Spring)") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(qtyText, { qtyText = it }, label = { Text("Qty") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(rateText, { rateText = it }, label = { Text("Rate ₹") }, modifier = Modifier.weight(1f))
                }
                if (catalogItems.isNotEmpty()) {
                    Text("Saved item catalogue", fontWeight = FontWeight.Bold)
                    catalogItems.forEach { saved ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(saved.first)
                                Text("Saved rate: ₹${money(saved.second)}", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = {
                                itemName = saved.first
                                rateText = money(saved.second)
                            }) { Text("Use") }
                            TextButton(onClick = {
                                catalogItems = catalogItems.filterNot { it.first == saved.first }
                                saveCatalog(context, catalogItems)
                            }) { Text("Delete") }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        catalogRate,
                        { catalogRate = it },
                        label = { Text("Save item at rate ₹") },
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        val r = catalogRate.toDoubleOrNull()
                        if (itemName.isBlank() || r == null || r < 0) {
                            status = "Enter item name and a valid saved rate first."
                        } else {
                            catalogItems = (catalogItems.filterNot { it.first.equals(itemName.trim(), true) } + (itemName.trim() to r))
                            saveCatalog(context, catalogItems)
                            rateText = money(r)
                            status = "Item saved to catalogue on this phone."
                        }
                    }, modifier = Modifier.alignByBaseline()) { Text("Save item") }
                }
                Button(
                    onClick = {
                        val q = qtyText.toDoubleOrNull()
                        val r = rateText.toDoubleOrNull()
                        if (itemName.isBlank() || q == null || q <= 0 || r == null || r < 0) {
                            status = "Enter a valid item name, quantity and rate."
                        } else {
                            items = items + BillItem(itemName.trim(), q, r)
                            itemName = ""
                            qtyText = "1"
                            rateText = "0"
                            status = "Item added."
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("＋ Add item") }

                if (items.isNotEmpty()) {
                    Text("Items in bill", fontWeight = FontWeight.Bold)
                    items.forEachIndexed { index, item ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name)
                                Text("${item.qty} × ₹${money(item.rate)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("₹${money(item.amount)}")
                            TextButton(onClick = { items = items.filterIndexed { i, _ -> i != index } }) { Text("Remove") }
                        }
                    }
                }

                Divider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Subtotal")
                    Text("₹${money(subtotal)}")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("GST invoice?")
                    Switch(checked = gstEnabled, onCheckedChange = { gstEnabled = it })
                }
                if (gstEnabled) {
                    OutlinedTextField(gstRate, { gstRate = it }, label = { Text("GST % (e.g. 18)") }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("GST amount")
                        Text("₹${money(gst)}")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("TOTAL AMOUNT", fontWeight = FontWeight.Bold)
                    Text("₹${money(total)}", fontWeight = FontWeight.Bold)
                }
                OutlinedTextField(receivedText, { receivedText = it }, label = { Text("Received amount ₹") }, modifier = Modifier.fillMaxWidth())
                Text("Balance due: ₹${money((total - received).coerceAtLeast(0.0))}")
                OutlinedTextField(upiId, { upiId = it }, label = { Text("UPI ID (optional)") }, modifier = Modifier.fillMaxWidth())

                Button(
                    onClick = {
                        if (items.isEmpty()) {
                            status = "Add at least one item before exporting."
                        } else {
                            val uri = createInvoicePdf(context, invoiceNo, date, customer, phone, items, subtotal, gstEnabled, gstRate.toDoubleOrNull() ?: 0.0, gst, total, received, upiId)
                            if (uri != null) {
                                val invoice = JSONObject().apply {
                                    put("invoiceNo", "GM-${invoiceNo.toString().padStart(4, '0')}")
                                    put("date", date)
                                    put("customer", customer)
                                    put("phone", phone)
                                    put("items", JSONArray().apply {
                                        items.forEach { item ->
                                            put(JSONObject().apply {
                                                put("name", item.name); put("qty", item.qty)
                                                put("rate", item.rate); put("amount", item.amount)
                                            })
                                        }
                                    })
                                    put("subtotal", subtotal)
                                    put("gstEnabled", gstEnabled)
                                    put("gstRate", gstRate.toDoubleOrNull() ?: 0.0)
                                    put("gst", gst)
                                    put("total", total)
                                    put("received", received)
                                    put("balance", (total - received).coerceAtLeast(0.0))
                                    put("upiId", upiId)
                                    put("createdAt", System.currentTimeMillis())
                                }
                                saveInvoiceLocally(context, invoice)
                                queueForCloudSync(context, invoice)
                                invoiceNo += 1
                                context.getSharedPreferences("garvit_billing", android.content.Context.MODE_PRIVATE)
                                    .edit().putInt("next_invoice_no", invoiceNo).apply()
                                status = "PDF saved. Invoice saved on this phone; cloud sync will retry when internet is available (Firebase setup required)."
                                Toast.makeText(context, "Invoice PDF saved", Toast.LENGTH_LONG).show()
                            } else status = "PDF could not be created."
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Create & save invoice PDF") }

                Button(
                    onClick = {
                        val uri = latestInvoiceUri
                        if (uri == null) status = "Create the PDF first."
                        else {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_TEXT, "GARVIT MODULE invoice")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Share invoice"))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Share PDF / WhatsApp") }

                OutlinedButton(
                    onClick = {
                        val result = cloudBackup(context, invoiceNo, customer, phone, items, subtotal, gst, total)
                        status = result
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cloud backup (Firebase setup required)") }

                Text(status, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Note: this starter stores the current bill in screen state only. Add a database and secure Firebase rules before using for live business records.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private var latestInvoiceUri: Uri? = null

private fun money(v: Double) = String.format(Locale.US, "%.2f", v)

private fun createInvoicePdf(
    context: android.content.Context,
    invoiceNo: Int,
    date: String,
    customer: String,
    phone: String,
    items: List<BillItem>,
    subtotal: Double,
    gstEnabled: Boolean,
    gstRate: Double,
    gst: Double,
    total: Double,
    received: Double,
    upiId: String
): Uri? {
    return try {
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = doc.startPage(pageInfo)
        val canvas = page.canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = android.graphics.Color.DKGRAY }
        val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(190, 160, 70)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRect(24f, 24f, 571f, 818f, gold)
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 22f
        canvas.drawText("GARVIT MODULE & SMPS", 42f, 62f, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 10f
        canvas.drawText("MODULE & SMPS • Contact details editable in project", 42f, 82f, paint)
        canvas.drawText(if (gstEnabled) "TAX INVOICE" else "BILL OF SUPPLY", 420f, 62f, paint)
        canvas.drawLine(36f, 98f, 559f, 98f, gold)
        canvas.drawText("Invoice No: GM-${invoiceNo.toString().padStart(4, '0')}", 42f, 122f, paint)
        canvas.drawText("Date: $date", 300f, 122f, paint)
        canvas.drawText("Bill To: ${customer.ifBlank { "Walk-in Customer" }}", 42f, 148f, paint)
        canvas.drawText("Mobile: ${phone.ifBlank { "—" }}", 42f, 166f, paint)
        canvas.drawLine(36f, 185f, 559f, 185f, gold)
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("No.", 42f, 208f, paint)
        canvas.drawText("ITEM / SERVICE", 75f, 208f, paint)
        canvas.drawText("QTY", 335f, 208f, paint)
        canvas.drawText("RATE", 405f, 208f, paint)
        canvas.drawText("TOTAL", 490f, 208f, paint)
        paint.typeface = Typeface.DEFAULT
        var y = 232f
        items.forEachIndexed { i, item ->
            if (y > 660f) return@forEachIndexed
            canvas.drawText("${i + 1}", 42f, y, paint)
            canvas.drawText(item.name.take(36), 75f, y, paint)
            canvas.drawText(item.qty.toString(), 335f, y, paint)
            canvas.drawText(money(item.rate), 405f, y, paint)
            canvas.drawText(money(item.amount), 490f, y, paint)
            y += 26f
        }
        canvas.drawLine(36f, y + 4f, 559f, y + 4f, gold)
        y += 28f
        canvas.drawText("Subtotal: ₹${money(subtotal)}", 370f, y, paint); y += 22f
        if (gstEnabled) {
            canvas.drawText("GST (${money(gstRate)}%): ₹${money(gst)}", 370f, y, paint); y += 22f
        }
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("TOTAL: ₹${money(total)}", 370f, y, paint); y += 22f
        paint.typeface = Typeface.DEFAULT
        canvas.drawText("Received: ₹${money(received)}", 370f, y, paint); y += 22f
        canvas.drawText("Balance due: ₹${money((total - received).coerceAtLeast(0.0))}", 370f, y, paint)
        canvas.drawText("UPI ID: ${upiId.ifBlank { "Add UPI ID in app" }}", 42f, 740f, paint)
        canvas.drawText("Thank you for your business!", 42f, 780f, paint)
        doc.finishPage(page)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "GARVIT_GM-${invoiceNo.toString().padStart(4, '0')}.pdf")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri != null) context.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
        doc.close()
        latestInvoiceUri = uri
        uri
    } catch (_: Exception) {
        null
    }
}

private fun cloudBackup(
    context: android.content.Context,
    invoiceNo: Int,
    customer: String,
    phone: String,
    items: List<BillItem>,
    subtotal: Double,
    gst: Double,
    total: Double
): String {
    return try {
        if (BuildConfig.FIREBASE_API_KEY.isBlank() ||
            BuildConfig.FIREBASE_APP_ID.isBlank() ||
            BuildConfig.FIREBASE_PROJECT_ID.isBlank()
        ) return "Cloud backup not configured. Add Firebase values in app/build.gradle.kts first."

        if (FirebaseApp.getApps(context).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .build()
            FirebaseApp.initializeApp(context, options)
        }
        val payload = hashMapOf(
            "invoiceNo" to "GM-${invoiceNo.toString().padStart(4, '0')}",
            "customer" to customer,
            "phone" to phone,
            "items" to items.map { mapOf("name" to it.name, "qty" to it.qty, "rate" to it.rate, "amount" to it.amount) },
            "subtotal" to subtotal,
            "gst" to gst,
            "total" to total,
            "createdAt" to System.currentTimeMillis()
        )
        FirebaseFirestore.getInstance().collection("invoices")
            .document("GM-${invoiceNo.toString().padStart(4, '0')}")
            .set(payload)
            .addOnSuccessListener { Toast.makeText(context, "Cloud backup saved", Toast.LENGTH_LONG).show() }
            .addOnFailureListener { Toast.makeText(context, "Backup failed: ${it.localizedMessage}", Toast.LENGTH_LONG).show() }
        "Cloud backup request sent. Check Firebase configuration and Firestore rules."
    } catch (e: Exception) {
        "Cloud backup unavailable: ${e.localizedMessage ?: "Firebase setup required"}"
    }
}



private fun loadCatalog(context: android.content.Context): List<Pair<String, Double>> {
    val prefs = context.getSharedPreferences("garvit_billing", android.content.Context.MODE_PRIVATE)
    val arr = JSONArray(prefs.getString("item_catalogue", "[]") ?: "[]")
    val result = mutableListOf<Pair<String, Double>>()
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val name = obj.optString("name")
        val rate = obj.optDouble("rate", 0.0)
        if (name.isNotBlank()) result.add(name to rate)
    }
    return result
}

private fun saveCatalog(context: android.content.Context, catalog: List<Pair<String, Double>>) {
    val arr = JSONArray()
    catalog.forEach { (name, rate) ->
        arr.put(JSONObject().apply { put("name", name); put("rate", rate) })
    }
    context.getSharedPreferences("garvit_billing", android.content.Context.MODE_PRIVATE)
        .edit().putString("item_catalogue", arr.toString()).apply()
}

private fun saveInvoiceLocally(context: android.content.Context, invoice: JSONObject) {
    val prefs = context.getSharedPreferences("garvit_billing", android.content.Context.MODE_PRIVATE)
    val all = JSONArray(prefs.getString("saved_invoices", "[]") ?: "[]")
    all.put(invoice)
    prefs.edit().putString("saved_invoices", all.toString()).apply()
}

private fun queueForCloudSync(context: android.content.Context, invoice: JSONObject) {
    val prefs = context.getSharedPreferences("garvit_billing", android.content.Context.MODE_PRIVATE)
    val pending = JSONArray(prefs.getString("pending_cloud_sync", "[]") ?: "[]")
    pending.put(invoice)
    prefs.edit().putString("pending_cloud_sync", pending.toString()).apply()

    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
    val request = OneTimeWorkRequestBuilder<InvoiceSyncWorker>()
        .setConstraints(constraints)
        .build()
    WorkManager.getInstance(context).enqueue(request)
}

/**
 * Offline-first sync worker. It retries when a network is available.
 * Firebase credentials and restrictive production Firestore rules are required.
 */
class InvoiceSyncWorker(
    appContext: android.content.Context,
    params: androidx.work.WorkerParameters
) : androidx.work.Worker(appContext, params) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("garvit_billing", android.content.Context.MODE_PRIVATE)
        if (BuildConfig.FIREBASE_API_KEY.isBlank() ||
            BuildConfig.FIREBASE_APP_ID.isBlank() ||
            BuildConfig.FIREBASE_PROJECT_ID.isBlank()
        ) return Result.retry()

        return try {
            if (FirebaseApp.getApps(applicationContext).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .build()
                FirebaseApp.initializeApp(applicationContext, options)
            }
            val pending = JSONArray(prefs.getString("pending_cloud_sync", "[]") ?: "[]")
            if (pending.length() == 0) return Result.success()

            val db = FirebaseFirestore.getInstance()
            // WorkManager is synchronous here; wait for each Firestore task to finish.
            for (i in 0 until pending.length()) {
                val obj = pending.getJSONObject(i)
                val invoiceId = obj.optString("invoiceNo")
                if (invoiceId.isBlank()) continue
                Tasks.await(db.collection("invoices").document(invoiceId).set(obj.toMap()))
            }
            prefs.edit().putString("pending_cloud_sync", "[]").apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

private fun JSONObject.toMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = get(key)
        map[key] = when (value) {
            JSONObject.NULL -> null
            is JSONObject -> value.toMap()
            is JSONArray -> (0 until value.length()).map { i ->
                val entry = value.get(i)
                if (entry is JSONObject) entry.toMap() else entry
            }
            else -> value
        }
    }
    return map
}
