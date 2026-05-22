const imageInput = document.getElementById('imageInput');
const fileNameDisplay = document.getElementById('fileName');
const uploadForm = document.getElementById('uploadForm');
const submitBtn = document.getElementById('submitBtn');
const resultDiv = document.getElementById('result');

imageInput.addEventListener('change', function() {
    if (this.files && this.files.length > 0) {
        fileNameDisplay.textContent = this.files[0].name;
    } else {
        fileNameDisplay.textContent = "Click to select a PNG";
    }
});

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

    submitBtn.disabled = true;
    submitBtn.textContent = "Processing...";
    resultDiv.innerHTML = "";

    try {
        const response = await fetch('/upload-map', {
            method: 'POST',
            body: formData
        });

        const data = await response.json();

        if (data.status === 'success') {
            // Create a local preview URL of the uploaded image
            const localImageUrl = URL.createObjectURL(file);

            let html = `
                <div style="color: #4CAF50; margin-bottom: 10px; font-weight: bold;">
                    Success! Generated ${data.map_ids.length} maps.
                </div>
                <div class="map-preview-wrapper">
                    <div class="map-grid" style="grid-template-columns: repeat(${gridX}, 1fr);">
            `;

            data.map_ids.forEach((id, index) => {
                const col = index % gridX;
                const row = Math.floor(index / gridX);
                const command = `/give @p filled_map[map_id=${id}]`;

                // Calculate the CSS background zoom and pan to isolate this specific chunk
                const bgSize = `${gridX * 100}% ${gridY * 100}%`;
                const bgPosX = gridX > 1 ? (col / (gridX - 1)) * 100 : 50;
                const bgPosY = gridY > 1 ? (row / (gridY - 1)) * 100 : 50;

                html += `
                    <div class="map-cell" 
                         style="background-image: url('${localImageUrl}'); background-size: ${bgSize}; background-position: ${bgPosX}% ${bgPosY}%;"
                         onclick="copyCommand(this, '${command}')">
                        <div class="overlay">
                            <span class="copy-hint">CLICK TO COPY</span>
                            <span class="cmd-text">${command}</span>
                        </div>
                    </div>
                `;
            });

            html += `
                    </div>
                </div>
                <p style="font-size: 0.85em; color: #aaa;">Hover over a piece of the map to see its command.</p>
            `;

            resultDiv.innerHTML = html;
        } else {
            resultDiv.innerHTML = `<span style="color: #f44336;">Error: ${data.message || "Something went wrong."}</span>`;
        }
    } catch (error) {
        console.error("Fetch failed!", error);
        resultDiv.innerHTML = `<span style="color: #f44336;">Failed to connect to the server or invalid JSON returned.</span>`;
    } finally {
        submitBtn.disabled = false;
        submitBtn.textContent = "Generate Map";
    }
});

// Global function to copy the command and show temporary visual feedback
window.copyCommand = function(cellElement, command) {
    navigator.clipboard.writeText(command).then(() => {
        const hintSpan = cellElement.querySelector('.copy-hint');
        const originalText = hintSpan.innerText;
        const originalColor = hintSpan.style.color;

        // Change text to visually confirm copy
        hintSpan.innerText = "COPIED!";
        hintSpan.style.color = "#4CAF50";

        // Reset after 1.5 seconds
        setTimeout(() => {
            hintSpan.innerText = originalText;
            hintSpan.style.color = originalColor;
        }, 1500);
    }).catch(err => {
        console.error('Failed to copy text: ', err);
        alert('Clipboard copy failed. Please manually copy the command.');
    });
};