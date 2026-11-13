package com.dtn.mesh.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.dtn.mesh.BuildConfig
import com.dtn.mesh.R
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.InnerTopBar
import com.dtn.mesh.ui.components.SectionHeader
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnType
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/**
 * One developer. [photoBias] = where the face is in the photo (x, y in -1..1), so the crop keeps
 * it in view. A null [github] / [linkedIn] hides that logo.
 */
data class TeamMember(
    val name: String,
    @DrawableRes val photo: Int,
    val github: String? = null,
    val linkedIn: String? = null,
    val photoBias: Pair<Float, Float> = 0f to 0f,
)

/** The team on Settings › About the team. Photos are in `res/drawable-nodpi/team_*.jpg|png`. */
object AboutTeam {
    const val PROJECT = "DTN Mesh Relay"
    const val TAGLINE = "An off-grid messenger that carries messages phone to phone when there's no network."

    val members: List<TeamMember> = listOf(
        TeamMember(
            "Vrishank Raina", R.drawable.team_vrishank,
            github = "https://github.com/hmm183",
            linkedIn = "https://www.linkedin.com/in/vrishank-raina/",
            photoBias = -0.7f to -0.2f,
        ),
        TeamMember(
            "Raushan Shrivastwa", R.drawable.team_raushan,
            github = "https://github.com/RaushanShrivastwa",
            linkedIn = "https://www.linkedin.com/in/raushan-shrivastwa/",
            photoBias = 0f to -0.2f,
        ),
        TeamMember(
            "Vishwanath Tiwari", R.drawable.team_vishwanath,
            github = "https://github.com/Vishwa5395",
            linkedIn = "https://www.linkedin.com/in/vishwanath-tiwari-779528287/",
            photoBias = 0f to -0.6f,
        ),
        TeamMember(
            "Priyanshu Upadhyay", R.drawable.team_priyanshu,
            github = "https://github.com/priyanshuu-02",
            linkedIn = "https://www.linkedin.com/in/priyanshu-upadhyay02/",
            photoBias = -0.5f to -0.2f,
        ),
    )

    val mentor: TeamMember = TeamMember(
        "Dr. Udit Narayana Kar", R.drawable.mentor_udit,
        linkedIn = "https://in.linkedin.com/in/dr-udit-narayana-kar-3b090b21",
    )

    /** What the app does (shown under "What we built"). */
    val built: List<String> = listOf(
        "Store-carry-forward delivery over Bluetooth LE and Wi-Fi Direct, phone to phone.",
        "A custom ESP32-S3 LoRa hub (Heltec V3) that floods messages hub to hub over long range.",
        "An optional internet relay (MQTT and a Cloudflare Worker) that reaches contacts anywhere.",
        "Six routing algorithms: PRoPHET, Stable PRoPHET, MaxProp, DiMaxProp, Epidemic and Double Q-learning.",
        "End-to-end encryption: only the person a message is for can read it.",
    )
}

private val GitHubInk = Color(0xFF181717)
private val LinkedInBlue = Color(0xFF0A66C2)

/**
 * About the team (`about/team`, the last row of Settings): one card per developer in a swipeable
 * pager (photo, name, GitHub / LinkedIn logos that open the profile), dots to jump between
 * them, then what the app does.
 */
@Composable
fun AboutTeamPage(onBack: () -> Unit) {
    val members = AboutTeam.members
    val pager = rememberPagerState { members.size }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val open: (String) -> Unit = { url ->
        // No browser installed (or a locked-down profile): say so instead of crashing.
        runCatching { uriHandler.openUri(url) }.onFailure { scope.launch { snackbars.showSnackbar("No app can open that link") } }
    }

    Scaffold(
        containerColor = DtnColors.Background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = { InnerTopBar("About the team", onBack = onBack, subtitle = AboutTeam.PROJECT) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        ) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Meet the developers", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = DtnColors.TextTitle)
                Spacer(Modifier.height(4.dp))
                Text("${members.size} people built ${AboutTeam.PROJECT}. Swipe to meet each one.", style = DtnType.RowSubtitle)
            }
            Spacer(Modifier.height(16.dp))

            HorizontalPager(
                state = pager,
                contentPadding = PaddingValues(horizontal = 36.dp),
                pageSpacing = 14.dp,
                modifier = Modifier.fillMaxWidth(),
            ) { page ->
                // Neighbouring cards peek in, a little smaller and faded.
                val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                MemberCard(
                    member = members[page],
                    position = "${page + 1} of ${members.size}",
                    onOpen = open,
                    modifier = Modifier.graphicsLayer {
                        val scale = lerp(1f, 0.9f, offset)
                        scaleX = scale
                        scaleY = scale
                        alpha = lerp(1f, 0.6f, offset)
                    },
                )
            }

            Spacer(Modifier.height(14.dp))
            PagerDots(pager, members.size, onSelect = { scope.launch { pager.animateScrollToPage(it) } },
                label = { members[it].name })

            Spacer(Modifier.height(24.dp))
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Meet the mentor", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = DtnColors.TextTitle)
                Spacer(Modifier.height(4.dp))
                Text("Project guidance and mentorship.", style = DtnType.RowSubtitle)
            }
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp),
            ) {
                MemberCard(
                    member = AboutTeam.mentor,
                    position = "Mentor",
                    onOpen = open,
                )
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(16.dp))
                SectionHeader("What we built")
                DtnCard {
                    Text(AboutTeam.TAGLINE, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextTitle)
                    Spacer(Modifier.height(8.dp))
                    AboutTeam.built.forEach { line ->
                        Text("•  $line", style = DtnType.RowSubtitle, modifier = Modifier.padding(bottom = 4.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Version ${BuildConfig.VERSION_NAME}", style = DtnType.Meta)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** A developer's card: photo with the name over a dark fade, then the profile logos. */
@Composable
private fun MemberCard(member: TeamMember, position: String, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Surface(
        modifier = modifier.fillMaxWidth().shadow(10.dp, shape, ambientColor = DtnColors.Primary, spotColor = DtnColors.Primary),
        shape = shape,
        color = DtnColors.Card,
        border = BorderStroke(1.dp, DtnColors.CardBorder),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(0.86f)) {
                Image(
                    painter = painterResource(member.photo),
                    contentDescription = "Photo of ${member.name}",
                    contentScale = ContentScale.Crop,
                    alignment = BiasAlignment(member.photoBias.first, member.photoBias.second),
                    modifier = Modifier.fillMaxSize(),
                )
                // Fade to dark at the bottom so the name stays readable on any photo.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to Color(0xE60B1220),
                        ),
                    ),
                )
                Surface(
                    shape = CircleShape, color = Color.Black.copy(alpha = 0.35f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) {
                    Text(position, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 18.dp, vertical = 16.dp)) {
                    Text(member.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                member.github?.let { url ->
                    LogoButton(DtnIcons.GitHub, tint = GitHubInk, label = "${member.name} on GitHub") { onOpen(url) }
                }
                member.linkedIn?.let { url ->
                    LogoButton(DtnIcons.LinkedIn, tint = LinkedInBlue, label = "${member.name} on LinkedIn") { onOpen(url) }
                }
            }
        }
    }
}

/** A round white button holding a brand logo in its own colour. */
@Composable
private fun LogoButton(logo: ImageVector, tint: Color, label: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = Color.White,
        border = BorderStroke(1.dp, DtnColors.CardBorder),
        shadowElevation = 3.dp,
        modifier = Modifier.size(56.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Open profile", onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(logo, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        }
    }
}

/** Dots under the pager; the current one is a wider blue pill. Each dot jumps to its card. */
@Composable
private fun PagerDots(pager: PagerState, count: Int, onSelect: (Int) -> Unit, label: (Int) -> String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            val selected = pager.currentPage == i
            val width by animateDpAsState(if (selected) 24.dp else 8.dp, label = "dot width")
            val color by animateColorAsState(if (selected) DtnColors.Primary else DtnColors.Grey.copy(alpha = 0.5f), label = "dot colour")
            Box(
                Modifier.size(width = 32.dp, height = 48.dp)
                    .clickable(role = Role.Tab, onClickLabel = "Show ${label(i)}") { onSelect(i) }
                    .semantics { contentDescription = "${label(i)}, ${i + 1} of $count" + if (selected) ", shown" else "" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.height(8.dp).width(width).clip(CircleShape).background(color))
            }
        }
    }
}
