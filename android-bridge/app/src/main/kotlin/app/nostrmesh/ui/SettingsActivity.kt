package app.nostrmesh.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.nostrmesh.BridgeApp
import app.nostrmesh.bridge.BridgeConfig
import app.nostrmesh.bridge.LoRaPreset
import app.nostrmesh.data.StoredDevice
import app.nostrmesh.data.shortKey
import app.nostrmesh.databinding.ActivitySettingsBinding
import app.nostrmesh.nostr.Bech32
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class SettingsActivity : AppCompatActivity() {
    private val st get() = BridgeApp.instance.settings
    private lateinit var content: LinearLayout
    private lateinit var relays: TextInputEditText
    private lateinit var region: TextInputEditText
    private lateinit var preset: TextInputEditText
    private lateinit var deviceList: LinearLayout

    private val profileText = mapOf(
        "road.report" to "Roadstr / Roadbeacon hlásenia (polícia, nehody…)",
        "road.vote" to "Roadstr potvrdenia hlásení",
        "libretag" to "Hlásenia LibreTag trackerov (#libretag, šifrované, most ich nečíta)",
        "dm4.ref" to "Správy zariadení (NIP-04, krátke kľúče z registra)",
        "dm4.full" to "Správy zariadení (NIP-04, plné kľúče)",
        "note" to "Krátke verejné poznámky (kind 1)",
        "generic" to "Ostatné udalosti",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }
        content = b.content

        section("Nostr relays", "Jeden na riadok. Na ne most posiela udalosti z rádia.")
        relays = field("wss://…", multiline = true).also { it.setText(st.relays.joinToString("\n")) }

        section("Čo posielať na Nostr", "Udalosti z rádia; podpis sa vždy overí. Poznámky a ostatné sú predvolene vypnuté, nech most nešíri spam.")
        for (p in BridgeConfig.ALL_UPLINK) {
            content.addView(MaterialSwitch(this).apply {
                text = profileText[p] ?: p
                isChecked = p in st.uplinkProfiles
                setOnCheckedChangeListener { _, on ->
                    st.uplinkProfiles = if (on) st.uplinkProfiles + p else st.uplinkProfiles - p
                }
            })
        }

        section("Príkazy pre LibreTag trackery (Nostr → rádio)",
            "Majiteľ vopred zverejní zapečatený príkaz. Most ho vyšle raz, keď zachytí hlásenie trackera: zero-hop, ak ho počul priamo, inak po obrátenej ceste do 3 hopov (len ak to MeshCore server vie), floodom len v režime LOST. S inými mostmi sa dohodne cez Nostr.")
        content.addView(MaterialSwitch(this).apply {
            text = "Vysielať príkazy pre trackery"
            isChecked = st.libretagCommands
            setOnCheckedChangeListener { _, on -> st.libretagCommands = on }
        })

        section("Správy do zariadení (Nostr → rádio)",
            "Predvolene vypnuté. Most potom pošle do rádia len NIP-04 správu od povolenej osoby pre zariadenie z registra, a to až keď sa zariadenie ozve: jeden paket, zero-hop alebo direct, nikdy flood.")
        content.addView(MaterialSwitch(this).apply {
            text = "Posielať správy do zariadení"
            isChecked = st.downlinkDm
            setOnCheckedChangeListener { _, on -> st.downlinkDm = on }
        })

        section("Zariadenia", "Register: kto smie písať ktorému zariadeniu.")
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(deviceList)
        content.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Pridať zariadenie"
            setOnClickListener { editDevice(null) }
        })
        renderDevices()

        section("Flood región", "Prázdne = nastavenie rádia v MeshCore Open.")
        region = field("napr. sk").also { it.setText(st.meshRegion) }

        section("Rádio (rozpočet airtime)", "SF/BW kHz/CR, ak ho MeshCore server sám neuvedie. Most vlastným vysielaním minie najviac 0,1 % času za hodinu.")
        preset = field("7/62.5/5").also { it.setText(st.loraPreset.toString()) }
    }

    override fun onPause() {
        super.onPause()
        val list = relays.text.toString().lines().map { it.trim() }.filter { it.startsWith("wss://") || it.startsWith("ws://") }
        if (list != st.relays) st.relays = list
        st.meshRegion = region.text.toString().trim()
        LoRaPreset.parse(preset.text.toString())?.let { if (it != st.loraPreset) st.loraPreset = it }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun section(title: String, help: String) {
        content.addView(TextView(this).apply {
            text = title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setPadding(0, dp(20), 0, dp(2))
        })
        content.addView(TextView(this).apply {
            text = help
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setPadding(0, 0, 0, dp(8))
        })
    }

    private fun field(hint: String, multiline: Boolean = false, parent: ViewGroup = content): TextInputEditText {
        val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        til.hint = hint
        val et = TextInputEditText(til.context)
        if (multiline) {
            et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
            et.minLines = 3
        } else et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        til.addView(et)
        parent.addView(til, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        return et
    }

    private fun renderDevices() {
        deviceList.removeAllViews()
        if (st.devices.isEmpty()) {
            deviceList.addView(TextView(this).apply { text = "Žiadne zariadenie."; setPadding(0, 0, 0, dp(8)) })
        }
        st.devices.forEachIndexed { i, d ->
            val card = MaterialCardView(this, null, com.google.android.material.R.attr.materialCardViewOutlinedStyle)
            card.addView(TextView(this).apply {
                val pk = Bech32.pubkey(d.pubkey)
                text = buildString {
                    append(d.name)
                    append("\n")
                    append(pk?.let { shortKey(it) } ?: "neplatný kľúč!")
                    append(if (d.sleeps) " · spí, správy čakajú" else " · stále počúva")
                }
                setPadding(dp(16), dp(12), dp(16), dp(12))
            })
            card.setOnClickListener { editDevice(i) }
            deviceList.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
    }

    private fun editDevice(index: Int?) {
        val cur = index?.let { st.devices[it] }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val name = field("Názov", parent = box).apply { setText(cur?.name ?: "") }
        val pub = field("npub zariadenia", parent = box).apply { setText(cur?.pubkey ?: "") }
        val owner = field("Tvoj npub (smie mu písať)", parent = box).apply { setText(cur?.owner ?: "") }
        val sleeps = MaterialSwitch(this).apply { text = "Zariadenie spí (správy čakajú, kým sa ozve)"; isChecked = cur?.sleeps ?: true }
        box.addView(sleeps)
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(if (cur == null) "Nové zariadenie" else cur.name)
            .setView(box)
            .setPositiveButton("Uložiť", null)
            .setNegativeButton("Zrušiť", null)
        if (index != null) dlg.setNeutralButton("Odstrániť") { _, _ ->
            st.devices = st.devices.toMutableList().also { it.removeAt(index) }
            renderDevices()
        }
        val d = dlg.show()
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val dev = StoredDevice(
                name = name.text.toString().trim().ifEmpty { "zariadenie" },
                pubkey = pub.text.toString().trim(),
                owner = owner.text.toString().trim().ifEmpty { null },
                sleeps = sleeps.isChecked,
            )
            val err = when {
                Bech32.pubkey(dev.pubkey) == null -> pub
                dev.owner != null && Bech32.pubkey(dev.owner) == null -> owner
                else -> null
            }
            if (err != null) {
                (err.parent.parent as? TextInputLayout)?.error = "Neplatné"
                return@setOnClickListener
            }
            st.devices = st.devices.toMutableList().also { if (index == null) it.add(dev) else it[index] = dev }
            renderDevices()
            d.dismiss()
        }
    }

}
