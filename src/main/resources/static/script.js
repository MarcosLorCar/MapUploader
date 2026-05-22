const imageInput = document.getElementById('imageInput');
const fileNameDisplay = document.getElementById('fileName');
const uploadForm = document.getElementById('uploadForm');
const submitBtn = document.getElementById('submitBtn');
const resultDiv = document.getElementById('result');

const urlId = window.location.pathname.substring(1);

imageInput.addEventListener('change', function() {
    if (this.files && this.files.length > 0) {
        fileNameDisplay.textContent = this.files[0].name;
    } else {
        fileNameDisplay.textContent = "Click to select an image";
    }
});

// Function to generate the HTML for a project
function createProjectHTML(project) {
    const imageUrl = `/previews/${project.id}.png`;

    // Convert timestamp to readable date
    const dateStr = new Date(project.timestamp).toLocaleString();

    let html = `
        <div class="project-card" style="background: #1a1a1a; padding: 15px; border-radius: 8px; border: 1px solid #444;">
            <div style="margin-bottom: 10px; font-size: 0.9em; color: #ccc;">
                <strong>Generated:</strong> ${dateStr} | <strong>Size:</strong> ${project.grid_x}x${project.grid_y}
            </div>
            <div class="map-grid" style="grid-template-columns: repeat(${project.grid_x}, 1fr); max-width: ${project.grid_x * 128}px; margin: 0 auto;">
    `;

    project.map_ids.forEach((mapId, index) => {
        const col = index % project.grid_x;
        const row = Math.floor(index / project.grid_x);

        const bgSize = `${project.grid_x * 100}% ${project.grid_y * 100}%`;
        const bgPosX = project.grid_x > 1 ? (col / (project.grid_x - 1)) * 100 : 50;
        const bgPosY = project.grid_y > 1 ? (row / (project.grid_y - 1)) * 100 : 50;

        html += `
            <div class="map-cell" 
                 style="background-image: url('${imageUrl}'); background-size: ${bgSize}; background-position: ${bgPosX}% ${bgPosY}%; border: 1px solid rgba(255,255,255,0.1);"
                 onclick="sendToGame(this, ${mapId})">
                <div class="overlay">
                    <span class="copy-hint">CLICK TO SEND</span>
                    <span class="cmd-text">Map #${mapId}</span>
                </div>
            </div>
        `;
    });

    html += `</div></div>`;
    return html;
}

async function loadHistory() {
    const gallery = document.getElementById('history-gallery');
    try {
        const response = await fetch(`/history/${urlId}`);
        const projects = await response.json();

        gallery.innerHTML = "";
        if (projects.length === 0) {
            gallery.innerHTML = "<p style='color: #888;'>No maps uploaded yet.</p>";
            return;
        }

        projects.forEach(project => {
            gallery.innerHTML += createProjectHTML(project);
        });
    } catch (err) {
        console.error("Failed to load history:", err);
        gallery.innerHTML = "<p style='color: #f44336;'>Failed to load gallery.</p>";
    }
}

uploadForm.addEventListener('submit', async function(e) {
    e.preventDefault();

    const file = imageInput.files[0];
    if (!file) return;

    const gridX = parseInt(document.getElementById('gridX').value, 10);
    const gridY = parseInt(document.getElementById('gridY').value, 10);

    const formData = new FormData();
    formData.append('image', file);
    formData.append('grid_x', gridX);
    formData.append('grid_y', gridY);
    formData.append('session_id', urlId);

    submitBtn.disabled = true;
    submitBtn.textContent = "Processing...";
    resultDiv.innerHTML = "";

    try {
        const response = await fetch('/upload-map', {
            method: 'POST',
            body: formData
        });

        const project = await response.json();

        if (project.id) { // Check if successful
            resultDiv.innerHTML = `<span style="color: #4CAF50; font-weight: bold;">Success! Map generated.</span>`;

            // Reload the history so the new upload appears at the top!
            await loadHistory();

            // Clear the file input
            uploadForm.reset();
            fileNameDisplay.textContent = "Click to select an image";
        } else {
            resultDiv.innerHTML = `<span style="color: #f44336;">Error processing map.</span>`;
        }
    } catch (error) {
        console.error("Fetch failed!", error);
        resultDiv.innerHTML = `<span style="color: #f44336;">Failed to connect to the server.</span>`;
    } finally {
        submitBtn.disabled = false;
        submitBtn.textContent = "Generate Map";
    }
});

window.sendToGame = async function(cellElement, mapId) {
    const hintSpan = cellElement.querySelector('.copy-hint');
    const originalText = hintSpan.innerText;
    const originalColor = hintSpan.style.color;

    // Visual feedback: Sending state
    hintSpan.innerText = "SENDING...";
    hintSpan.style.color = "#FFC107"; // Yellow

    try {
        const response = await fetch('/give-map', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            // Send the map ID and the scoreboard number from the URL
            body: JSON.stringify({
                map_id: mapId,
                player_number: parseInt(urlId, 10)
            })
        });

        const data = await response.json();

        if (data.status === 'success') {
            hintSpan.innerText = "SENT!";
            hintSpan.style.color = "#4CAF50"; // Green
        } else {
            hintSpan.innerText = "FAILED!";
            hintSpan.style.color = "#f44336"; // Red
            console.error(data.message);
        }
    } catch (err) {
        console.error('Network error: ', err);
        hintSpan.innerText = "ERROR!";
        hintSpan.style.color = "#f44336";
    }

    // Reset visual state after 2 seconds
    setTimeout(() => {
        hintSpan.innerText = originalText;
        hintSpan.style.color = originalColor;
    }, 2000);
};

loadHistory();